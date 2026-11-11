//! File operations that put up with Windows: a virus scanner or the search indexer may hold a file for a moment after a
//! process has exited, so renames and deletes are tried again for a few seconds before they count as failed.

use std::fs;
use std::io::{self, Write};
use std::path::Path;
use std::sync::atomic::{AtomicU32, Ordering};
use std::thread::sleep;
use std::time::Duration;

/// How often a rename is tried (100 ms apart). Tests lower it so that an expected failure does not take five seconds.
pub static RENAME_ATTEMPTS: AtomicU32 = AtomicU32::new(50);

#[cfg(test)]
thread_local! {
    /// Tests name a destination here to make every rename to it fail, standing in for a file that is in use.
    pub static FAIL_RENAME_TO: std::cell::RefCell<Option<std::path::PathBuf>> = const { std::cell::RefCell::new(None) };
}

pub fn rename(from: &Path, to: &Path) -> io::Result<()> {
    #[cfg(test)]
    if FAIL_RENAME_TO.with(|f| f.borrow().as_deref() == Some(to)) {
        return Err(io::Error::other("in use (simulated)"));
    }
    let attempts = RENAME_ATTEMPTS.load(Ordering::Relaxed).max(1);
    let mut last = None;
    for attempt in 0..attempts {
        match fs::rename(from, to) {
            Ok(()) => return Ok(()),
            Err(e) if e.kind() == io::ErrorKind::NotFound => return Err(e),
            Err(e) => last = Some(e),
        }
        if attempt + 1 < attempts {
            sleep(Duration::from_millis(100));
        }
    }
    Err(last.unwrap_or_else(|| io::Error::other("rename failed")))
}

/// Deletes a file or a whole folder. Something that is already gone is not an error.
pub fn remove(path: &Path) -> io::Result<()> {
    let mut last = None;
    for attempt in 0..10 {
        let result = match fs::symlink_metadata(path) {
            Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(()),
            Err(e) => Err(e),
            Ok(meta) if meta.is_dir() => fs::remove_dir_all(path),
            Ok(_) => fs::remove_file(path),
        };
        match result {
            Ok(()) => return Ok(()),
            Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(()),
            Err(e) => last = Some(e),
        }
        if attempt < 9 {
            sleep(Duration::from_millis(100));
        }
    }
    Err(last.unwrap_or_else(|| io::Error::other("delete failed")))
}

/// Writes a file so that it is either complete or absent: into a temporary neighbour first, then renamed over.
pub fn write_atomic(path: &Path, bytes: &[u8]) -> io::Result<()> {
    let mut temp = path.as_os_str().to_owned();
    temp.push(".tmp");
    let temp = Path::new(&temp);
    {
        let mut file = fs::File::create(temp)?;
        file.write_all(bytes)?;
        file.sync_all()?;
    }
    rename(temp, path)
}

/// Creates a marker file and makes sure it has reached the disk.
pub fn mark(path: &Path) -> io::Result<()> {
    let file = fs::File::create(path)?;
    file.sync_all()
}
