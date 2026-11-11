//! Where things are inside the install folder.
//!
//! ```text
//! MyApp/
//!   MyApp.exe            the stub
//!   jpack.json           the installed manifest
//!   jpack.state.json     releases that failed to start (not offered again)
//!   jpack.lock           held while the app runs (shared) and while files are swapped (exclusive)
//!   jpack.update.lock    held by the one stub that is downloading
//!   jpack.log
//!   runtime/ app/ ...    one folder per part
//!   update/              a downloaded release waiting to be swapped in
//!     dl/                zips being downloaded
//!     <part>/            unpacked new parts
//!     manifest.json      the new manifest
//!     READY              written last: everything above is complete and verified
//!     APPLYING           a swap has started (so a second attempt continues it)
//!   old/                 the parts the last swap replaced, kept until the new release has started once
//!   trash/               parts of a release that was rolled back, about to be deleted
//! ```

use std::path::{Path, PathBuf};

#[derive(Clone, Debug)]
pub struct Layout {
    pub root: PathBuf,
}

impl Layout {
    pub fn new(root: impl Into<PathBuf>) -> Layout {
        Layout { root: root.into() }
    }

    pub fn manifest(&self) -> PathBuf {
        self.root.join("jpack.json")
    }

    pub fn state(&self) -> PathBuf {
        self.root.join("jpack.state.json")
    }

    pub fn lock(&self) -> PathBuf {
        self.root.join("jpack.lock")
    }

    pub fn update_lock(&self) -> PathBuf {
        self.root.join("jpack.update.lock")
    }

    pub fn log(&self) -> PathBuf {
        self.root.join("jpack.log")
    }

    pub fn part(&self, name: &str) -> PathBuf {
        self.root.join(name)
    }

    pub fn update(&self) -> PathBuf {
        self.root.join("update")
    }

    pub fn downloads(&self) -> PathBuf {
        self.update().join("dl")
    }

    pub fn staged_manifest(&self) -> PathBuf {
        self.update().join("manifest.json")
    }

    pub fn ready(&self) -> PathBuf {
        self.update().join("READY")
    }

    pub fn applying(&self) -> PathBuf {
        self.update().join("APPLYING")
    }

    pub fn old(&self) -> PathBuf {
        self.root.join("old")
    }

    pub fn old_manifest(&self) -> PathBuf {
        self.old().join("jpack.json")
    }

    pub fn rollback_marker(&self) -> PathBuf {
        self.old().join("ROLLBACK")
    }

    pub fn trash(&self) -> PathBuf {
        self.root.join("trash")
    }

    /// True while the release that was swapped in last has not yet proved that it starts.
    pub fn on_trial(&self) -> bool {
        self.old_manifest().exists() && !self.applying().exists() && !self.rollback_marker().exists()
    }
}

/// Resolves a manifest path (always written with `/`) against the install folder.
pub fn resolve(root: &Path, relative: &str) -> PathBuf {
    let mut path = root.to_path_buf();
    for segment in relative.split(['/', '\\']) {
        if !segment.is_empty() && segment != "." {
            path.push(segment);
        }
    }
    path
}
