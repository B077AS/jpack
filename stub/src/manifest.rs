//! The manifest: one JSON file that describes a release (its parts and how to start it).
//!
//! The same format is used in two places: `jpack.json` next to the stub says what is installed, and the file behind
//! `update.url` says what the newest release is.

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;

/// The newest manifest format this stub understands.
pub const SUPPORTED_FORMAT: u32 = 1;

/// The part that holds the stub itself. It is not a folder: it replaces the running executable.
pub const STUB_PART: &str = "stub";

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Manifest {
    pub format: u32,
    #[serde(default)]
    pub name: String,
    pub version: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub update: Option<UpdateConfig>,
    #[serde(default)]
    pub parts: BTreeMap<String, Part>,
    pub launch: Launch,
    /// Reserved for signed manifests. Ignored for now.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub signature: Option<serde_json::Value>,
    /// Only in the manifest published with a release (the installed copy cannot contain the hash of the file it is in):
    /// the AppImage of this release, which AppImage installs update themselves with.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub app_image: Option<AppImage>,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct AppImage {
    /// File name (next to the manifest) or a full URL.
    pub file: String,
    pub sha256: String,
    pub size: u64,
    /// The block index that lets an installed AppImage fetch only what changed (see `delta`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub blocks: Option<BlocksFile>,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct BlocksFile {
    pub file: String,
    pub sha256: String,
    pub size: u64,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct UpdateConfig {
    /// Where the newest manifest is. Part files are looked up next to it unless they are full URLs.
    pub url: String,
    #[serde(default)]
    pub policy: Policy,
    /// With the `background` policy: how often a running app looks again. Default 4.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub interval_hours: Option<u32>,
}

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Default)]
#[serde(rename_all = "lowercase")]
pub enum Policy {
    /// The stub looks for updates by itself while the app runs and applies them when the app is closed.
    #[default]
    Background,
    /// The stub only does what the app (or its launcher) asks through the Java library.
    Manual,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Part {
    /// File name of the zip (next to the manifest), or a full URL.
    pub file: String,
    /// SHA-256 of the zip, lower-case hex.
    pub sha256: String,
    /// Size of the zip in bytes.
    pub size: u64,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Launch {
    /// The entry the stub starts first.
    pub start: String,
    pub entries: BTreeMap<String, Entry>,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Entry {
    /// Folder of the Java runtime, relative to the install folder.
    #[serde(default = "default_runtime")]
    pub runtime: String,
    /// The Java executable, relative to the install folder. Default: `<runtime>/bin/javaw.exe` or `<runtime>/bin/java`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub java: Option<String>,
    #[serde(default)]
    pub jvm_args: Vec<String>,
    #[serde(default)]
    pub classpath: Vec<String>,
    #[serde(default)]
    pub module_path: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub main_class: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub main_module: Option<String>,
    #[serde(default)]
    pub args: Vec<String>,
    #[serde(default)]
    pub env: BTreeMap<String, String>,
    /// Windows: start with `java.exe` (a console) and not `javaw.exe`.
    #[serde(default)]
    pub console: bool,
}

fn default_runtime() -> String {
    "runtime".to_string()
}

impl Manifest {
    pub fn parse(bytes: &[u8]) -> Result<Manifest, String> {
        let manifest: Manifest = serde_json::from_slice(bytes).map_err(|e| format!("the manifest is not valid: {e}"))?;
        manifest.validate()?;
        Ok(manifest)
    }

    /// Refuses anything that could make the stub touch files outside the install folder, or start nothing.
    pub fn validate(&self) -> Result<(), String> {
        if self.format == 0 {
            return Err("the manifest has no format number".into());
        }
        if self.version.trim().is_empty() {
            return Err("the manifest has no version".into());
        }
        if let Some(image) = &self.app_image {
            let hex = |s: &str| s.len() == 64 && s.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b));
            if image.file.trim().is_empty() || !hex(&image.sha256) {
                return Err("the AppImage entry is incomplete".into());
            }
            if let Some(blocks) = &image.blocks {
                if blocks.file.trim().is_empty() || !hex(&blocks.sha256) {
                    return Err("the block index entry is incomplete".into());
                }
            }
        }
        for (name, part) in &self.parts {
            if !is_part_name(name) {
                return Err(format!("'{name}' is not a usable part name"));
            }
            if part.sha256.len() != 64 || !part.sha256.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) {
                return Err(format!("part '{name}' has no valid sha256"));
            }
            if part.file.trim().is_empty() {
                return Err(format!("part '{name}' names no file"));
            }
        }
        if !self.launch.entries.contains_key(&self.launch.start) {
            return Err(format!("the start entry '{}' is not defined", self.launch.start));
        }
        for (name, entry) in &self.launch.entries {
            if entry.main_class.is_none() && entry.main_module.is_none() {
                return Err(format!("entry '{name}' has neither a main class nor a main module"));
            }
            let mut paths: Vec<&String> = vec![&entry.runtime];
            paths.extend(entry.java.iter());
            paths.extend(entry.classpath.iter());
            paths.extend(entry.module_path.iter());
            for path in paths {
                if !is_safe_relative(path) {
                    return Err(format!("entry '{name}': '{path}' must be a path inside the install folder"));
                }
            }
        }
        Ok(())
    }

    /// Identifies a release by what it contains: two manifests with the same fingerprint install the same thing.
    pub fn fingerprint(&self) -> String {
        let mut hasher = Sha256::new();
        hasher.update(self.version.as_bytes());
        hasher.update(b"\n");
        for (name, part) in &self.parts {
            hasher.update(name.as_bytes());
            hasher.update(b"=");
            hasher.update(part.sha256.as_bytes());
            hasher.update(b"\n");
        }
        hasher.update(serde_json::to_string(&self.launch).unwrap_or_default().as_bytes());
        hex(&hasher.finalize())
    }
}

pub fn hex(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        out.push_str(&format!("{b:02x}"));
    }
    out
}

/// A part becomes a folder directly inside the install folder, so its name must be a plain one that the stub does not use.
pub fn is_part_name(name: &str) -> bool {
    if name.is_empty() || name.len() > 64 || name.starts_with('.') {
        return false;
    }
    if !name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_' || b == b'.') {
        return false;
    }
    let lower = name.to_ascii_lowercase();
    !(lower == "update" || lower == "old" || lower == "trash" || lower.starts_with("jpack"))
}

/// True for a relative path that stays inside the folder it is resolved against.
pub fn is_safe_relative(path: &str) -> bool {
    if path.is_empty() || path.starts_with('/') || path.starts_with('\\') || path.contains(':') {
        return false;
    }
    !path.split(['/', '\\']).any(|segment| segment == "..")
}

#[cfg(test)]
mod tests {
    use super::*;

    pub fn sample(version: &str) -> Manifest {
        let json = format!(
            r#"{{"format":1,"name":"Demo","version":"{version}",
                "parts":{{"app":{{"file":"app.zip","sha256":"{}","size":3}}}},
                "launch":{{"start":"app","entries":{{"app":{{"classpath":["app/demo.jar"],"mainClass":"demo.Main"}}}}}}}}"#,
            "a".repeat(64)
        );
        Manifest::parse(json.as_bytes()).unwrap()
    }

    #[test]
    fn a_plain_manifest_is_read_with_its_defaults() {
        let manifest = sample("1.0.0");
        assert_eq!(manifest.launch.entries["app"].runtime, "runtime");
        assert!(manifest.update.is_none());
        assert!(!manifest.launch.entries["app"].console);
    }

    #[test]
    fn part_names_that_would_collide_with_the_stubs_own_folders_are_refused() {
        for name in ["update", "old", "trash", "jpack.json", "JPACK", "..", ".hidden", "a/b", "a\\b", ""] {
            assert!(!is_part_name(name), "{name}");
        }
        for name in ["app", "runtime", "launcher", "stub", "my-files_2.0"] {
            assert!(is_part_name(name), "{name}");
        }
    }

    #[test]
    fn paths_that_leave_the_install_folder_are_refused() {
        for path in ["../x", "a/../../x", "/etc/passwd", "\\\\server\\share", "C:\\x", "c:x", ""] {
            assert!(!is_safe_relative(path), "{path}");
        }
        for path in ["app/demo.jar", "app/*", "runtime", "a\\b.jar"] {
            assert!(is_safe_relative(path), "{path}");
        }
    }

    #[test]
    fn a_manifest_whose_entry_points_outside_is_refused() {
        let mut manifest = sample("1.0.0");
        manifest.launch.entries.get_mut("app").unwrap().classpath = vec!["../evil.jar".into()];
        assert!(manifest.validate().is_err());
    }

    #[test]
    fn a_manifest_without_its_start_entry_is_refused() {
        let mut manifest = sample("1.0.0");
        manifest.launch.start = "missing".into();
        assert!(manifest.validate().is_err());
    }

    #[test]
    fn the_fingerprint_follows_the_version_the_parts_and_the_launch_settings() {
        let base = sample("1.0.0");
        assert_eq!(base.fingerprint(), sample("1.0.0").fingerprint());
        assert_ne!(base.fingerprint(), sample("1.0.1").fingerprint());

        let mut other_part = base.clone();
        other_part.parts.get_mut("app").unwrap().sha256 = "b".repeat(64);
        assert_ne!(base.fingerprint(), other_part.fingerprint());

        let mut other_launch = base.clone();
        other_launch.launch.entries.get_mut("app").unwrap().jvm_args.push("-Xmx1g".into());
        assert_ne!(base.fingerprint(), other_launch.fingerprint());
    }
}
