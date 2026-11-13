//! Finding out whether there is a newer release, and downloading it into `update/`.
//!
//! Nothing installed is touched here. The result is a folder that holds the unpacked new parts, the new manifest and,
//! written last, a `READY` marker; `apply` does the rest when no process of the app is running.

use crate::fsx;
use crate::layout::Layout;
use crate::lock::Lock;
use crate::log;
use crate::manifest::{hex, Manifest, Part, SUPPORTED_FORMAT};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::time::Duration;

/// A manifest larger than this is not a manifest.
const MAX_MANIFEST_BYTES: u64 = 1024 * 1024;
const DOWNLOAD_ATTEMPTS: u32 = 3;

/// What a look at the update address found.
pub struct Check {
    pub available: bool,
    /// The version behind the update address (the installed one when nothing is available).
    pub version: String,
    /// Bytes that would be downloaded: the zips of the parts that changed.
    pub size: u64,
    /// True when this release is already downloaded and only waits to be swapped in.
    pub staged: bool,
    remote: Option<Remote>,
}

impl Check {
    /// The release a check found and where its manifest was fetched from, for the AppImage update.
    pub(crate) fn found(&self) -> Option<(&Manifest, &str)> {
        self.remote.as_ref().map(|remote| (&remote.manifest, remote.url.as_str()))
    }
}

struct Remote {
    manifest: Manifest,
    raw: Vec<u8>,
    url: String,
}

/// Releases that were swapped in and did not start. They are not offered again.
#[derive(Serialize, Deserialize, Default)]
struct State {
    #[serde(default)]
    skip: Vec<String>,
}

pub struct Updater {
    pub(crate) layout: Layout,
    pub(crate) agent: ureq::Agent,
}

impl Updater {
    pub fn new(layout: Layout) -> Updater {
        Updater { layout, agent: agent() }
    }

    /// Fetches the newest manifest and compares it with what is installed.
    pub fn check(&self, installed: &Manifest) -> Result<Check, String> {
        let config = installed.update.as_ref().ok_or("this installation has no update address")?;
        let url = config.url.trim().to_string();
        require_safe_url(&url)?;
        let raw = self.fetch_small(&url)?;
        let remote = Manifest::parse(&raw)?;
        if remote.format > SUPPORTED_FORMAT {
            return Err(format!("version {} needs a newer jpack than this installation has", remote.version));
        }
        let fingerprint = remote.fingerprint();
        if fingerprint == installed.fingerprint() {
            return Ok(Check { available: false, version: installed.version.clone(), size: 0, staged: false, remote: None });
        }
        if self.read_state().skip.contains(&fingerprint) {
            log!("version {} is not offered: it failed to start here before", remote.version);
            return Ok(Check { available: false, version: installed.version.clone(), size: 0, staged: false, remote: None });
        }
        let size = changed_parts(installed, &remote).iter().map(|(_, part)| part.size).sum();
        let staged = self.staged().is_some_and(|staged| staged.fingerprint() == fingerprint);
        Ok(Check { available: true, version: remote.version.clone(), size, staged, remote: Some(Remote { manifest: remote, raw, url }) })
    }

    /// The release that is downloaded and waiting, if any.
    pub fn staged(&self) -> Option<Manifest> {
        if !self.layout.ready().exists() {
            return None;
        }
        let bytes = fs::read(self.layout.staged_manifest()).ok()?;
        Manifest::parse(&bytes).ok()
    }

    /// Downloads the release a check found. `progress` gets (bytes so far, bytes in total).
    pub fn stage(&self, installed: &Manifest, check: &Check, progress: &mut dyn FnMut(u64, u64)) -> Result<(), String> {
        let remote = check.remote.as_ref().ok_or("there is nothing to download")?;
        if check.staged {
            return Ok(());
        }
        // Only one stub downloads at a time, however many windows of the app are open.
        let lock = Lock::update(&self.layout).map_err(|e| format!("the install folder cannot be written to: {e}"))?;
        if !lock.try_exclusive() {
            return Err("another running copy of this app is already downloading the update".into());
        }
        self.stage_locked(installed, remote, check.size, progress)
    }

    fn stage_locked(&self, installed: &Manifest, remote: &Remote, total: u64, progress: &mut dyn FnMut(u64, u64)) -> Result<(), String> {
        let layout = &self.layout;
        if layout.applying().exists() {
            return Err("an update is being swapped in".into());
        }
        let changed = changed_parts(installed, &remote.manifest);
        let downloads = layout.downloads();
        fs::create_dir_all(&downloads).map_err(|e| format!("cannot create {}: {e}", downloads.display()))?;

        // Whatever an earlier attempt left is dropped, except partial downloads of exactly the zips needed now.
        let _ = fsx::remove(&layout.ready());
        for entry in fs::read_dir(layout.update()).map_err(|e| e.to_string())?.filter_map(Result::ok) {
            if entry.path() != downloads {
                fsx::remove(&entry.path()).map_err(|e| format!("cannot clear {}: {e}", entry.path().display()))?;
            }
        }
        for entry in fs::read_dir(&downloads).map_err(|e| e.to_string())?.filter_map(Result::ok) {
            let name = entry.file_name().to_string_lossy().into_owned();
            let wanted = changed.iter().any(|(_, part)| name == format!("{}.zip", part.sha256) || name == format!("{}.zip.part", part.sha256));
            if !wanted {
                let _ = fsx::remove(&entry.path());
            }
        }

        let mut done: u64 = 0;
        progress(0, total);
        for (name, part) in &changed {
            let zip = downloads.join(format!("{}.zip", part.sha256));
            if !(zip.exists() && file_sha256(&zip).is_ok_and(|sha| sha == part.sha256)) {
                let _ = fsx::remove(&zip);
                let url = resolve_url(&remote.url, &part.file)?;
                require_safe_url(&url)?;
                let before = done;
                self.download(&url, &zip, part, &mut |so_far| progress(before + so_far, total))
                    .map_err(|e| format!("downloading part '{name}' failed: {e}"))?;
            }
            done += part.size;
            progress(done, total);
        }

        for (name, part) in &changed {
            let zip = downloads.join(format!("{}.zip", part.sha256));
            let target = layout.update().join(name);
            let staged = if part.packed { extract(&zip, &target) } else { store(&zip, &target, &part.file) };
            staged.map_err(|e| format!("unpacking part '{name}' failed: {e}"))?;
        }

        fsx::write_atomic(&layout.staged_manifest(), &remote.raw).map_err(|e| format!("cannot write the new manifest: {e}"))?;
        let _ = fsx::remove(&downloads);
        fsx::mark(&layout.ready()).map_err(|e| format!("cannot finish the download: {e}"))?;
        log!("version {} is downloaded ({} bytes) and waits to be swapped in", remote.manifest.version, total);
        Ok(())
    }

    /// Downloads one zip, continuing a partial file when the server allows it, and accepts it only with the right hash.
    pub(crate) fn download(&self, url: &str, zip: &Path, part: &Part, progress: &mut dyn FnMut(u64)) -> Result<(), String> {
        let partial = PathBuf::from(format!("{}.part", zip.display()));
        let mut last = String::new();
        for attempt in 1..=DOWNLOAD_ATTEMPTS {
            match self.download_once(url, &partial, part, progress) {
                Ok(()) => return fsx::rename(&partial, zip).map_err(|e| format!("cannot keep the download: {e}")),
                Err(Failure::Retry(message)) => last = message,
                Err(Failure::Corrupt(message)) => {
                    // Continuing a file that turned out wrong would only repeat the mistake.
                    let _ = fsx::remove(&partial);
                    last = message;
                }
                Err(Failure::Fatal(message)) => return Err(message),
            }
            log!("download attempt {attempt} of {DOWNLOAD_ATTEMPTS} failed: {last}");
            if attempt < DOWNLOAD_ATTEMPTS {
                std::thread::sleep(Duration::from_millis(500 * u64::from(attempt)));
            }
        }
        Err(last)
    }

    fn download_once(&self, url: &str, partial: &Path, part: &Part, progress: &mut dyn FnMut(u64)) -> Result<(), Failure> {
        let mut have = fs::metadata(partial).map(|m| m.len()).unwrap_or(0);
        if have > part.size {
            let _ = fsx::remove(partial);
            have = 0;
        }
        let mut request = self.agent.get(url);
        if have > 0 {
            request = request.set("Range", &format!("bytes={have}-"));
        }
        let response = match request.call() {
            Ok(response) => response,
            Err(ureq::Error::Status(416, _)) => return Err(Failure::Corrupt("the server no longer has the rest of this file".into())),
            Err(ureq::Error::Status(code, _)) if (400..500).contains(&code) => return Err(Failure::Fatal(format!("{url} answered {code}"))),
            Err(ureq::Error::Status(code, _)) => return Err(Failure::Retry(format!("{url} answered {code}"))),
            Err(e) => return Err(Failure::Retry(e.to_string())),
        };

        let mut hasher = Sha256::new();
        let mut file = if have > 0 && response.status() == 206 {
            // The server sends the rest: what is on disk is hashed first so that the whole file is checked at the end.
            let mut existing = File::open(partial).map_err(|e| Failure::Fatal(e.to_string()))?;
            let mut buffer = vec![0u8; 64 * 1024];
            loop {
                let n = existing.read(&mut buffer).map_err(|e| Failure::Fatal(e.to_string()))?;
                if n == 0 {
                    break;
                }
                hasher.update(&buffer[..n]);
            }
            OpenOptions::new().append(true).open(partial).map_err(|e| Failure::Fatal(e.to_string()))?
        } else {
            have = 0;
            File::create(partial).map_err(|e| Failure::Fatal(format!("cannot write {}: {e}", partial.display())))?
        };

        progress(have);
        let mut reader = response.into_reader();
        let mut buffer = vec![0u8; 64 * 1024];
        loop {
            let n = reader.read(&mut buffer).map_err(|e| Failure::Retry(format!("the connection broke: {e}")))?;
            if n == 0 {
                break;
            }
            have += n as u64;
            if have > part.size {
                return Err(Failure::Corrupt("the file is larger than the manifest says".into()));
            }
            file.write_all(&buffer[..n]).map_err(|e| Failure::Fatal(format!("cannot write {}: {e}", partial.display())))?;
            hasher.update(&buffer[..n]);
            progress(have);
        }
        file.sync_all().map_err(|e| Failure::Fatal(e.to_string()))?;
        drop(file);

        if have < part.size {
            return Err(Failure::Retry(format!("the download stopped at {have} of {} bytes", part.size)));
        }
        if hex(&hasher.finalize()) != part.sha256 {
            return Err(Failure::Corrupt("the file does not match the hash in the manifest".into()));
        }
        Ok(())
    }

    pub(crate) fn fetch_small(&self, url: &str) -> Result<Vec<u8>, String> {
        let response = match self.agent.get(url).call() {
            Ok(response) => response,
            Err(ureq::Error::Status(code, _)) => return Err(format!("{url} answered {code}")),
            Err(e) => return Err(format!("{url} could not be reached: {e}")),
        };
        let mut bytes = Vec::new();
        response.into_reader().take(MAX_MANIFEST_BYTES + 1).read_to_end(&mut bytes).map_err(|e| format!("reading {url} failed: {e}"))?;
        if bytes.len() as u64 > MAX_MANIFEST_BYTES {
            return Err(format!("{url} is too large to be a manifest"));
        }
        Ok(bytes)
    }

    /// Remembers a release that did not start, so that it is not downloaded and tried over and over.
    pub fn skip(&self, release: &Manifest) {
        let mut state = self.read_state();
        let fingerprint = release.fingerprint();
        if !state.skip.contains(&fingerprint) {
            state.skip.push(fingerprint);
            // Only the latest few matter: a publisher replaces a broken release, it does not bring old ones back.
            let excess = state.skip.len().saturating_sub(20);
            state.skip.drain(..excess);
            if let Ok(json) = serde_json::to_vec_pretty(&state) {
                if let Err(e) = fsx::write_atomic(&self.layout.state(), &json) {
                    log!("cannot remember the failed version: {e}");
                }
            }
        }
    }

    fn read_state(&self) -> State {
        fs::read(self.layout.state()).ok().and_then(|bytes| serde_json::from_slice(&bytes).ok()).unwrap_or_default()
    }
}

enum Failure {
    /// Worth another try, continuing where this one stopped.
    Retry(String),
    /// What was downloaded is wrong: start this file again from nothing.
    Corrupt(String),
    /// Trying again cannot help.
    Fatal(String),
}

/// The parts whose zip differs from the installed one (new parts included), in the order they are downloaded.
fn changed_parts<'a>(installed: &Manifest, remote: &'a Manifest) -> Vec<(&'a String, &'a Part)> {
    remote.parts.iter().filter(|(name, part)| installed.parts.get(*name).is_none_or(|have| have.sha256 != part.sha256)).collect()
}

fn agent() -> ureq::Agent {
    let builder = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(20))
        .timeout_read(Duration::from_secs(60))
        .redirects(8)
        .try_proxy_from_env(true)
        .user_agent(concat!("jpack/", env!("CARGO_PKG_VERSION")));
    #[cfg(windows)]
    let builder = match native_tls::TlsConnector::new() {
        Ok(connector) => builder.tls_connector(std::sync::Arc::new(connector)),
        Err(e) => {
            log!("the system's TLS could not be set up, https will fail: {e}");
            builder
        }
    };
    builder.build()
}

/// Updates must come over https. Plain http is accepted only from this machine, which is what tests and local trials use.
pub(crate) fn require_safe_url(url: &str) -> Result<(), String> {
    if url.starts_with("https://") {
        return Ok(());
    }
    if let Some(rest) = url.strip_prefix("http://") {
        let authority = rest.split(['/', '?', '#']).next().unwrap_or("");
        let host = authority.rsplit('@').next().unwrap_or("");
        let host = if let Some(bracketed) = host.strip_prefix('[') { bracketed.split(']').next().unwrap_or("") } else { host.split(':').next().unwrap_or("") };
        if host == "127.0.0.1" || host == "localhost" || host == "::1" {
            return Ok(());
        }
    }
    Err(format!("'{url}' is not an https address"))
}

/// A part file is a full URL, or a name looked up next to the manifest.
pub(crate) fn resolve_url(manifest_url: &str, file: &str) -> Result<String, String> {
    if file.starts_with("https://") || file.starts_with("http://") {
        return Ok(file.to_string());
    }
    if file.contains("..") || file.starts_with('/') || file.contains('\\') || file.contains(':') {
        return Err(format!("'{file}' is not a usable file name"));
    }
    let without_query = manifest_url.split(['?', '#']).next().unwrap_or(manifest_url);
    let scheme_end = without_query.find("://").map(|i| i + 3).unwrap_or(0);
    let base = match without_query[scheme_end..].rfind('/') {
        Some(slash) => &without_query[..scheme_end + slash + 1],
        None => return Ok(format!("{without_query}/{file}")),
    };
    Ok(format!("{base}{file}"))
}

pub(crate) fn file_sha256(path: &Path) -> std::io::Result<String> {
    let mut file = File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buffer = vec![0u8; 64 * 1024];
    loop {
        let n = file.read(&mut buffer)?;
        if n == 0 {
            break;
        }
        hasher.update(&buffer[..n]);
    }
    Ok(hex(&hasher.finalize()))
}

/// Unpacks a part. An entry that would land outside the target folder makes the whole zip unusable.
/// A part that is not a zip: the downloaded file becomes the one file of the part's folder, under its published name.
fn store(file: &Path, target: &Path, name: &str) -> Result<(), String> {
    fsx::remove(target).map_err(|e| format!("cannot clear {}: {e}", target.display()))?;
    fs::create_dir_all(target).map_err(|e| format!("cannot create {}: {e}", target.display()))?;
    let path = target.join(name);
    fs::copy(file, &path).map_err(|e| format!("cannot write {}: {e}", path.display()))?;
    Ok(())
}

fn extract(zip: &Path, target: &Path) -> Result<(), String> {
    fsx::remove(target).map_err(|e| format!("cannot clear {}: {e}", target.display()))?;
    fs::create_dir_all(target).map_err(|e| format!("cannot create {}: {e}", target.display()))?;
    let file = File::open(zip).map_err(|e| e.to_string())?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| format!("not a zip: {e}"))?;
    for index in 0..archive.len() {
        let mut entry = archive.by_index(index).map_err(|e| e.to_string())?;
        let relative = entry.enclosed_name().ok_or_else(|| format!("'{}' points outside the part", entry.name()))?;
        let path = target.join(relative);
        if entry.is_dir() {
            fs::create_dir_all(&path).map_err(|e| e.to_string())?;
            continue;
        }
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        let mut out = File::create(&path).map_err(|e| format!("cannot write {}: {e}", path.display()))?;
        std::io::copy(&mut entry, &mut out).map_err(|e| format!("cannot unpack {}: {e}", path.display()))?;
        #[cfg(unix)]
        if let Some(mode) = entry.unix_mode() {
            use std::os::unix::fs::PermissionsExt;
            let _ = fs::set_permissions(&path, fs::Permissions::from_mode(mode & 0o777));
        }
    }
    mark_executables(target);
    Ok(())
}

/// Zips written by Java carry no Unix permissions, so a part lists its programs in `.jpack-exec`, one path per line.
fn mark_executables(target: &Path) {
    let list = target.join(".jpack-exec");
    let Ok(text) = fs::read_to_string(&list) else { return };
    let _ = fs::remove_file(&list);
    for line in text.lines().map(str::trim).filter(|line| !line.is_empty()) {
        if !crate::manifest::is_safe_relative(line) {
            continue;
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = fs::set_permissions(crate::layout::resolve(target, line), fs::Permissions::from_mode(0o755));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn part_files_are_looked_up_next_to_the_manifest() {
        let manifest = "https://github.com/o/r/releases/latest/download/jpack-windows-x64.json";
        assert_eq!(resolve_url(manifest, "app-12.zip").unwrap(), "https://github.com/o/r/releases/latest/download/app-12.zip");
        assert_eq!(resolve_url("https://example.com/m.json?token=1", "a.zip").unwrap(), "https://example.com/a.zip");
        assert_eq!(resolve_url("https://example.com", "a.zip").unwrap(), "https://example.com/a.zip");
        assert_eq!(resolve_url(manifest, "https://cdn.example.com/a.zip").unwrap(), "https://cdn.example.com/a.zip");
        for bad in ["../a.zip", "/a.zip", r"a\b.zip", "file:a.zip"] {
            assert!(resolve_url(manifest, bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn only_https_and_this_machine_are_accepted() {
        for good in ["https://example.com/m.json", "http://127.0.0.1:8080/m.json", "http://localhost/m.json", "http://[::1]:9/m.json"] {
            assert!(require_safe_url(good).is_ok(), "{good}");
        }
        for bad in ["http://example.com/m.json", "http://127.0.0.1.evil.com/m.json", "http://localhost@evil.com/m.json", "ftp://x/m.json", "file:///c:/m.json", "m.json"] {
            assert!(require_safe_url(bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn a_plain_file_part_is_copied_into_its_folder_under_its_published_name() {
        let root = std::env::temp_dir().join(format!("jpack-store-test-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(&root).unwrap();
        let download = root.join("downloaded");
        fs::write(&download, b"jar bytes").unwrap();
        let target = root.join("update").join("app");
        fs::create_dir_all(&target).unwrap();
        fs::write(target.join("left-over"), b"from an earlier attempt").unwrap();

        store(&download, &target, "komm-1.0.0.jar").unwrap();

        assert_eq!(fs::read(target.join("komm-1.0.0.jar")).unwrap(), b"jar bytes");
        assert!(!target.join("left-over").exists(), "the part's folder holds only the new file");
        let _ = fs::remove_dir_all(&root);
    }
}
