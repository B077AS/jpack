//! The two locks, which must not leave files behind in the install folder or anywhere else.
//!
//! * the **install lock**: every running stub holds it shared while its program runs; a swap of files needs it exclusively, so
//!   it only happens when no window of the app is open;
//! * the **update lock**: held by the one stub that is downloading.
//!
//! Where a lock lives:
//!
//! * Linux/macOS: on something that exists anyway. The install lock is a `flock` on the install folder, the update lock one on
//!   `jpack.json` (it is only ever replaced by a swap, and a swap cannot happen while any stub runs, so the lock cannot move
//!   under a holder). Nothing is created, and nothing is left behind when a stub is killed.
//! * Windows: a folder cannot be locked, and `jpack.json` must stay readable, so a small file in the temp folder named after a
//!   hash of the install folder's path. The last stub to leave deletes it. (Delete-on-close does not do: the first handle to
//!   close makes the file unopenable for the stubs that start next.) A stub that is killed leaves one empty file in the temp
//!   folder; the next run of that install reuses and removes it.

use crate::layout::Layout;
use std::fs::File;
use std::io;

pub struct Lock {
    file: File,
    /// The lock file that exists for this lock's sake alone.
    #[cfg(windows)]
    path: std::path::PathBuf,
}

impl Lock {
    pub fn install(layout: &Layout) -> io::Result<Lock> {
        #[cfg(windows)]
        return Lock::in_temp_folder(layout, "install");
        #[cfg(not(windows))]
        return Ok(Lock { file: File::open(&layout.root)? });
    }

    pub fn update(layout: &Layout) -> io::Result<Lock> {
        #[cfg(windows)]
        return Lock::in_temp_folder(layout, "update");
        #[cfg(not(windows))]
        return Ok(Lock { file: File::open(layout.manifest())? });
    }

    #[cfg(windows)]
    fn in_temp_folder(layout: &Layout, kind: &str) -> io::Result<Lock> {
        use sha2::{Digest, Sha256};
        let root = std::fs::canonicalize(&layout.root).unwrap_or_else(|_| layout.root.clone());
        let hash = Sha256::digest(root.to_string_lossy().to_lowercase().as_bytes());
        let id: String = hash[..8].iter().map(|byte| format!("{byte:02x}")).collect();
        let path = std::env::temp_dir().join(format!("jpack-{id}.{kind}.lock"));
        let file = std::fs::OpenOptions::new().create(true).truncate(false).write(true).open(&path)?;
        Ok(Lock { file, path })
    }

    /// Gives up what is held and tries for the exclusive lock. On `false` nothing is held.
    pub fn try_exclusive(&self) -> bool {
        let _ = self.file.unlock();
        self.file.try_lock().is_ok()
    }

    /// Gives up what is held and takes the shared lock, waiting while somebody holds it exclusively.
    pub fn shared(&self) -> io::Result<()> {
        let _ = self.file.unlock();
        self.file.lock_shared()
    }
}

impl Drop for Lock {
    fn drop(&mut self) {
        #[cfg(windows)]
        {
            // The last one out removes the file; with somebody else still holding it, this fails and the file stays.
            let _ = self.file.unlock();
            if self.file.try_lock().is_ok() {
                let _ = std::fs::remove_file(&self.path);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn install_folder(name: &str) -> Layout {
        let root = std::env::temp_dir().join(format!("jpack-lock-test-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();
        std::fs::write(root.join("jpack.json"), "{}").unwrap();
        Layout::new(root)
    }

    #[test]
    fn running_programs_share_the_install_lock_and_a_swap_waits_for_all_of_them() {
        let layout = install_folder("share");
        let first = Lock::install(&layout).unwrap();
        let second = Lock::install(&layout).unwrap();
        first.shared().unwrap();
        second.shared().unwrap();
        assert!(!first.try_exclusive(), "another window is still open");
        // (try_exclusive gave up the shared lock of `first`: only `second` is left, so a third cannot swap yet either)
        let third = Lock::install(&layout).unwrap();
        assert!(!third.try_exclusive());
        drop(second);
        assert!(third.try_exclusive(), "nobody else is left");
        drop(first);
        drop(third);
        let _ = std::fs::remove_dir_all(&layout.root);
    }

    #[test]
    fn only_one_stub_downloads_at_a_time() {
        let layout = install_folder("update");
        let first = Lock::update(&layout).unwrap();
        assert!(first.try_exclusive());
        let second = Lock::update(&layout).unwrap();
        assert!(!second.try_exclusive());
        drop(first);
        assert!(second.try_exclusive());
        let _ = std::fs::remove_dir_all(&layout.root);
    }

    #[test]
    fn no_file_is_left_in_the_install_folder() {
        let layout = install_folder("clean");
        let before = std::fs::read_dir(&layout.root).unwrap().count();
        drop(Lock::install(&layout).unwrap());
        let update = Lock::update(&layout).unwrap();
        assert!(update.try_exclusive());
        drop(update);
        assert_eq!(std::fs::read_dir(&layout.root).unwrap().count(), before);
        let _ = std::fs::remove_dir_all(&layout.root);
    }

    #[cfg(windows)]
    #[test]
    fn the_last_one_out_removes_the_lock_file() {
        let layout = install_folder("remove");
        let first = Lock::install(&layout).unwrap();
        let second = Lock::install(&layout).unwrap();
        let path = first.path.clone();
        first.shared().unwrap();
        second.shared().unwrap();
        drop(first);
        assert!(path.exists(), "a window is still open");
        let third = Lock::install(&layout).unwrap();
        third.shared().unwrap();
        drop(second);
        assert!(path.exists(), "another one has just started");
        drop(third);
        assert!(!path.exists(), "nobody is left");
        let _ = std::fs::remove_dir_all(&layout.root);
    }
}
