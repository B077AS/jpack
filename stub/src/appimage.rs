//! Updating an AppImage: the program runs from a read-only image, so an update replaces the image file itself.
//!
//! The new file is built next to the old one (`<name>.jpack-new`) from the blocks the old one already has and the blocks
//! fetched with HTTP range requests (`delta`), checked against the hash in the release manifest, and then renamed over the
//! old file. The running program keeps working from the old file until it exits; the next start is the new version.

use crate::delta::{self, Index};
use crate::fsx;
use crate::log;
use crate::manifest::{hex, AppImage};
use crate::update::{file_sha256, require_safe_url, resolve_url, Updater};
use sha2::{Digest, Sha256};
use std::fs::{File, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

/// True when the folder holding the AppImage can take a new file: the update is written next to the old one.
pub fn can_replace(appimage: &Path) -> bool {
    let Some(dir) = appimage.parent() else { return false };
    let probe = dir.join(format!(".jpack-probe-{}", std::process::id()));
    let ok = File::create(&probe).is_ok();
    let _ = std::fs::remove_file(probe);
    ok
}

fn with_suffix(path: &Path, suffix: &str) -> PathBuf {
    let mut name = path.as_os_str().to_owned();
    name.push(suffix);
    name.into()
}

impl Updater {
    /// Replaces `target` (the AppImage file) with the AppImage the release at `manifest_url` describes.
    /// `progress` gets (bytes fetched so far, bytes to fetch in all).
    pub fn update_app_image(&self, release: &AppImage, manifest_url: &str, target: &Path, progress: &mut dyn FnMut(u64, u64)) -> Result<(), String> {
        let lock_path = with_suffix(target, ".jpack-lock");
        let lock = OpenOptions::new().create(true).truncate(false).write(true).open(&lock_path);
        let lock = lock.map_err(|e| format!("the folder of the AppImage cannot be written to: {e}"))?;
        if lock.try_lock().is_err() {
            return Err("another running copy of this app is already downloading the update".into());
        }
        let result = self.update_locked(release, manifest_url, target, progress);
        let _ = lock.unlock();
        let _ = std::fs::remove_file(lock_path);
        result
    }

    fn update_locked(&self, release: &AppImage, manifest_url: &str, target: &Path, progress: &mut dyn FnMut(u64, u64)) -> Result<(), String> {
        let new_file = with_suffix(target, ".jpack-new");
        let _ = fsx::remove(&new_file);
        let url = resolve_url(manifest_url, &release.file)?;
        require_safe_url(&url)?;

        let result = match &release.blocks {
            Some(blocks) => {
                let index_url = resolve_url(manifest_url, &blocks.file)?;
                require_safe_url(&index_url)?;
                let bytes = self.fetch_small(&index_url)?;
                if hex(&Sha256::digest(&bytes)) != blocks.sha256 {
                    return Err("the block index does not match the hash in the manifest".into());
                }
                let index = Index::parse(&bytes)?;
                if index.file_size != release.size {
                    return Err("the block index describes a different file than the manifest".into());
                }
                self.build_from_blocks(&index, &url, target, &new_file, progress)
            }
            None => self.download_whole(&url, release, &new_file, progress),
        };
        if let Err(e) = result {
            let _ = fsx::remove(&new_file);
            return Err(e);
        }

        let actual = file_sha256(&new_file).map_err(|e| e.to_string());
        match actual {
            Ok(hash) if hash == release.sha256 => {}
            Ok(_) => {
                let _ = fsx::remove(&new_file);
                return Err("the downloaded AppImage does not match the hash in the manifest".into());
            }
            Err(e) => {
                let _ = fsx::remove(&new_file);
                return Err(e);
            }
        }
        keep_permissions(target, &new_file);
        fsx::rename(&new_file, target).map_err(|e| {
            let _ = fsx::remove(&new_file);
            format!("cannot replace {}: {e}", target.display())
        })?;
        log!("the AppImage was replaced by version {} of the release", release.file);
        Ok(())
    }

    fn build_from_blocks(&self, index: &Index, url: &str, old: &Path, new_file: &Path, progress: &mut dyn FnMut(u64, u64)) -> Result<(), String> {
        let mut old_file = File::open(old).map_err(|e| format!("cannot read the installed AppImage: {e}"))?;
        let local = delta::find_local(&mut old_file, index, &mut |_| {}).map_err(|e| format!("cannot read the installed AppImage: {e}"))?;
        let total = index.missing_bytes(&local);
        log!("AppImage update: {} of {} bytes have to be fetched", total, index.file_size);
        progress(0, total);

        let mut out = File::create(new_file).map_err(|e| format!("cannot write {}: {e}", new_file.display()))?;
        // A server that ignores range requests answers with the whole file: it is kept once and sliced from then on.
        let mut whole: Option<Vec<u8>> = None;
        let mut so_far = 0u64;
        let hash = delta::assemble(
            &mut old_file,
            &local,
            index,
            &mut out,
            &mut |start, end| self.fetch_range(url, start, end, &mut whole),
            &mut |n| {
                so_far += n;
                progress(so_far, total);
            },
        )?;
        out.sync_all().map_err(|e| e.to_string())?;
        log!("AppImage update: built the new file (sha256 {hash})");
        Ok(())
    }

    /// The bytes `start..end` of the file at `url`.
    fn fetch_range(&self, url: &str, start: u64, end: u64, whole: &mut Option<Vec<u8>>) -> Result<Vec<u8>, String> {
        if let Some(all) = whole {
            return slice(all, start, end);
        }
        let mut last = String::new();
        for attempt in 1..=3u32 {
            let response = self.agent.get(url).set("Range", &format!("bytes={}-{}", start, end - 1)).call();
            match response {
                Ok(response) if response.status() == 206 => {
                    let mut bytes = Vec::with_capacity((end - start) as usize);
                    match response.into_reader().take(end - start + 1).read_to_end(&mut bytes) {
                        Ok(_) if bytes.len() as u64 == end - start => return Ok(bytes),
                        Ok(_) => last = format!("the server sent {} bytes for a request of {}", bytes.len(), end - start),
                        Err(e) => last = format!("the connection broke: {e}"),
                    }
                }
                Ok(response) if response.status() == 200 => {
                    let mut all = Vec::new();
                    response.into_reader().read_to_end(&mut all).map_err(|e| format!("the connection broke: {e}"))?;
                    log!("the server ignores range requests; the whole file was fetched");
                    let part = slice(&all, start, end);
                    *whole = Some(all);
                    return part;
                }
                Ok(response) => return Err(format!("{url} answered {}", response.status())),
                Err(ureq::Error::Status(code, _)) if (400..500).contains(&code) => return Err(format!("{url} answered {code}")),
                Err(e) => last = e.to_string(),
            }
            log!("range request attempt {attempt} of 3 failed: {last}");
            std::thread::sleep(std::time::Duration::from_millis(400 * u64::from(attempt)));
        }
        Err(last)
    }

    /// No block index: the whole file is fetched, continuing a partial download when there is one.
    fn download_whole(&self, url: &str, release: &AppImage, new_file: &Path, progress: &mut dyn FnMut(u64, u64)) -> Result<(), String> {
        let part = crate::manifest::Part { file: release.file.clone(), sha256: release.sha256.clone(), size: release.size };
        progress(0, release.size);
        self.download(url, new_file, &part, &mut |so_far| progress(so_far, release.size)).map_err(|e| format!("downloading the AppImage failed: {e}"))
    }
}

fn slice(all: &[u8], start: u64, end: u64) -> Result<Vec<u8>, String> {
    if end as usize > all.len() {
        return Err("the file on the server is shorter than the manifest says".into());
    }
    Ok(all[start as usize..end as usize].to_vec())
}

/// The new file is executable like the old one was (and at least executable by its owner).
fn keep_permissions(old: &Path, new_file: &Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = std::fs::metadata(old).map(|m| m.permissions().mode()).unwrap_or(0o755) | 0o100;
        let _ = std::fs::set_permissions(new_file, std::fs::Permissions::from_mode(mode & 0o7777));
    }
    #[cfg(not(unix))]
    let _ = (old, new_file);
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fsx;
    use crate::layout::Layout;
    use crate::manifest::BlocksFile;
    use std::io::{BufRead, BufReader};
    use std::net::TcpListener;
    use std::sync::atomic::{AtomicU32, Ordering};
    use std::sync::{Arc, Mutex};

    static COUNTER: AtomicU32 = AtomicU32::new(0);

    fn noise(len: usize, seed: u64) -> Vec<u8> {
        let mut state = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        (0..len)
            .map(|_| {
                state = state.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
                (state >> 33) as u8
            })
            .collect()
    }

    /// A web server for the tests: answers GET for `files`, with range support unless `ranges` is false.
    struct Server {
        base: String,
        requests: Arc<Mutex<Vec<String>>>,
    }

    fn serve(files: Vec<(&'static str, Vec<u8>)>, ranges: bool) -> Server {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://127.0.0.1:{}", listener.local_addr().unwrap().port());
        let requests = Arc::new(Mutex::new(Vec::new()));
        let seen = requests.clone();
        std::thread::spawn(move || {
            for stream in listener.incoming().flatten() {
                let files = files.clone();
                let seen = seen.clone();
                std::thread::spawn(move || {
                    let mut reader = BufReader::new(stream.try_clone().unwrap());
                    let mut stream = stream;
                    let mut line = String::new();
                    reader.read_line(&mut line).unwrap();
                    let path = line.split_whitespace().nth(1).unwrap_or("/").to_string();
                    let mut range = None;
                    loop {
                        let mut header = String::new();
                        if reader.read_line(&mut header).unwrap_or(0) <= 2 {
                            break;
                        }
                        if let Some(value) = header.to_ascii_lowercase().strip_prefix("range: bytes=") {
                            let (from, to) = value.trim().split_once('-').unwrap();
                            range = Some((from.parse::<usize>().unwrap(), to.parse::<usize>().unwrap()));
                        }
                    }
                    seen.lock().unwrap().push(format!("{path} {range:?}"));
                    let Some((_, body)) = files.iter().find(|(name, _)| format!("/{name}") == path) else {
                        let _ = stream.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                        return;
                    };
                    let (status, slice) = match range {
                        Some((from, to)) if ranges => ("206 Partial Content", &body[from..=to.min(body.len() - 1)]),
                        _ => ("200 OK", &body[..]),
                    };
                    let head = format!("HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", slice.len());
                    let _ = stream.write_all(head.as_bytes());
                    let _ = stream.write_all(slice);
                });
            }
        });
        Server { base, requests }
    }

    fn workdir() -> PathBuf {
        let dir = std::env::temp_dir().join(format!("jpack-appimage-{}-{}", std::process::id(), COUNTER.fetch_add(1, Ordering::SeqCst)));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn release(new: &[u8], with_blocks: bool, block_size: usize) -> (AppImage, Vec<u8>) {
        let index = Index::build(new, block_size).to_bytes();
        let image = AppImage {
            file: "new.AppImage".into(),
            sha256: hex(&Sha256::digest(new)),
            size: new.len() as u64,
            blocks: with_blocks.then(|| BlocksFile { file: "new.AppImage.blocks".into(), sha256: hex(&Sha256::digest(&index)), size: index.len() as u64 }),
        };
        (image, index)
    }

    fn updater(dir: &Path) -> Updater {
        Updater::new(Layout::new(dir))
    }

    #[test]
    fn an_appimage_is_updated_by_fetching_only_what_changed() {
        let old = noise(600_000, 1);
        let mut new = old[..200_000].to_vec();
        new.extend_from_slice(&noise(3000, 77));
        new.extend_from_slice(&old[200_000..]);
        let (image, index) = release(&new, true, 4096);
        let server = serve(vec![("new.AppImage", new.clone()), ("new.AppImage.blocks", index)], true);
        let dir = workdir();
        let target = dir.join("App.AppImage");
        std::fs::write(&target, &old).unwrap();

        let mut last = (0, 0);
        updater(&dir).update_app_image(&image, &format!("{}/manifest.json", server.base), &target, &mut |a, b| last = (a, b)).unwrap();

        assert_eq!(std::fs::read(&target).unwrap(), new);
        assert!(last.1 < 20_000, "only the blocks around the insertion are fetched, not {}", last.1);
        assert_eq!(last.0, last.1, "the progress ends complete");
        let fetched: usize = server.requests.lock().unwrap().iter().filter(|r| r.contains("Some")).count();
        assert!(fetched >= 1);
        assert!(!with_suffix(&target, ".jpack-new").exists(), "no half-built file is left");
        assert!(!with_suffix(&target, ".jpack-lock").exists());
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn a_server_that_ignores_range_requests_still_gives_the_right_file() {
        let old = noise(100_000, 2);
        let mut new = old.clone();
        new[50_000] ^= 1;
        new.extend_from_slice(&noise(5000, 3));
        let (image, index) = release(&new, true, 4096);
        let server = serve(vec![("new.AppImage", new.clone()), ("new.AppImage.blocks", index)], false);
        let dir = workdir();
        let target = dir.join("App.AppImage");
        std::fs::write(&target, &old).unwrap();

        updater(&dir).update_app_image(&image, &format!("{}/manifest.json", server.base), &target, &mut |_, _| {}).unwrap();

        assert_eq!(std::fs::read(&target).unwrap(), new);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn without_a_block_index_the_whole_file_is_fetched() {
        let old = noise(1000, 4);
        let new = noise(80_000, 5);
        let (image, _) = release(&new, false, 4096);
        let server = serve(vec![("new.AppImage", new.clone())], true);
        let dir = workdir();
        let target = dir.join("App.AppImage");
        std::fs::write(&target, &old).unwrap();

        updater(&dir).update_app_image(&image, &format!("{}/manifest.json", server.base), &target, &mut |_, _| {}).unwrap();

        assert_eq!(std::fs::read(&target).unwrap(), new);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn a_file_that_does_not_match_its_hash_never_replaces_the_installed_one() {
        let old = noise(50_000, 6);
        let new = noise(60_000, 7);
        let (mut image, index) = release(&new, true, 4096);
        image.sha256 = "0".repeat(64);
        let server = serve(vec![("new.AppImage", new), ("new.AppImage.blocks", index)], true);
        let dir = workdir();
        let target = dir.join("App.AppImage");
        std::fs::write(&target, &old).unwrap();

        let result = updater(&dir).update_app_image(&image, &format!("{}/manifest.json", server.base), &target, &mut |_, _| {});

        assert!(result.unwrap_err().contains("hash"));
        assert_eq!(std::fs::read(&target).unwrap(), old, "the installed file is untouched");
        assert!(!with_suffix(&target, ".jpack-new").exists());
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn a_block_index_that_does_not_match_the_manifest_is_refused() {
        let new = noise(60_000, 8);
        let (image, mut index) = release(&new, true, 4096);
        index[30] ^= 1;
        let server = serve(vec![("new.AppImage", new), ("new.AppImage.blocks", index)], true);
        let dir = workdir();
        let target = dir.join("App.AppImage");
        std::fs::write(&target, b"old").unwrap();

        let result = updater(&dir).update_app_image(&image, &format!("{}/manifest.json", server.base), &target, &mut |_, _| {});

        assert!(result.unwrap_err().contains("block index"));
        assert_eq!(std::fs::read(&target).unwrap(), b"old");
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn a_missing_file_on_the_server_is_an_error_not_a_hang() {
        let new = noise(60_000, 9);
        let (image, index) = release(&new, true, 4096);
        let server = serve(vec![("new.AppImage.blocks", index)], true);
        let dir = workdir();
        let target = dir.join("App.AppImage");
        std::fs::write(&target, b"old").unwrap();

        let result = updater(&dir).update_app_image(&image, &format!("{}/manifest.json", server.base), &target, &mut |_, _| {});

        assert!(result.is_err());
        assert_eq!(std::fs::read(&target).unwrap(), b"old");
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn a_folder_that_cannot_be_written_to_is_noticed() {
        assert!(can_replace(&workdir().join("App.AppImage")));
        assert!(!can_replace(Path::new("/no/such/folder/App.AppImage")));
        let _ = fsx::RENAME_ATTEMPTS.load(Ordering::Relaxed);
    }
}
