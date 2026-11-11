//! Swapping a downloaded release in, and taking it out again if it does not start.
//!
//! Nothing here downloads or decides: it only moves folders, and only while no process of the app is running (the caller
//! holds the exclusive lock). Every step is a rename inside the install folder, so a swap takes milliseconds whatever the
//! size of the parts.
//!
//! The steps are written so that they can be repeated: each one looks at what is on disk and does only what is still
//! missing. A power cut in the middle therefore costs nothing: the next start finds `update/APPLYING` and finishes the job
//! before it starts anything, so the app never runs with one part old and one part new.

use crate::fsx;
use crate::layout::Layout;
use crate::log;
use crate::manifest::{Manifest, STUB_PART};
use std::fs;
use std::path::Path;

#[derive(Debug)]
pub enum ApplyError {
    /// The swap could not be done now (a file was in use) and was undone completely. The old release is intact and the
    /// download is kept, so it is tried again later.
    Deferred(String),
    /// The swap failed and could not be undone either. The app must not be started; the next start tries to finish.
    Broken(String),
}

/// Swaps in the release waiting in `update/`, if there is one. Returns the manifest that is installed afterwards.
pub fn apply(layout: &Layout, exe: &Path) -> Result<Option<Manifest>, ApplyError> {
    let resuming = layout.applying().exists();
    if !resuming && !layout.ready().exists() {
        return Ok(None);
    }
    match swap_in(layout, exe, resuming) {
        Ok(installed) => Ok(installed),
        Err(cause) => {
            log!("swapping the update in failed: {cause}");
            match undo(layout) {
                Ok(()) => Err(ApplyError::Deferred(cause)),
                Err(second) => Err(ApplyError::Broken(format!("{cause}; and putting the old files back failed: {second}"))),
            }
        }
    }
}

fn swap_in(layout: &Layout, exe: &Path, resuming: bool) -> Result<Option<Manifest>, String> {
    let staged = layout.staged_manifest();
    let new = if staged.exists() {
        match read(&staged) {
            Ok(manifest) => manifest,
            Err(e) if !resuming => {
                log!("the downloaded update is unusable and is discarded: {e}");
                discard_staging(layout);
                return Ok(None);
            }
            Err(e) => return Err(e),
        }
    } else if resuming {
        // The new manifest is already in place: only the tidying up was left.
        read(&layout.manifest())?
    } else {
        log!("the downloaded update has no manifest and is discarded");
        discard_staging(layout);
        return Ok(None);
    };

    if !resuming {
        // Whatever an earlier swap kept is no longer needed: this release replaces it.
        fsx::remove(&layout.old()).map_err(|e| format!("cannot clear {}: {e}", layout.old().display()))?;
        fsx::remove(&layout.trash()).map_err(|e| format!("cannot clear {}: {e}", layout.trash().display()))?;
    }
    fs::create_dir_all(layout.old()).map_err(|e| format!("cannot create {}: {e}", layout.old().display()))?;
    if !resuming {
        fsx::mark(&layout.applying()).map_err(|e| format!("cannot write {}: {e}", layout.applying().display()))?;
    }

    let previous = if layout.old_manifest().exists() { read(&layout.old_manifest())? } else { read(&layout.manifest())? };

    for name in new.parts.keys().filter(|name| *name != STUB_PART) {
        let incoming = layout.update().join(name);
        if !incoming.is_dir() {
            continue; // unchanged, or already swapped in by the attempt this one continues
        }
        let installed = layout.part(name);
        let kept = layout.old().join(name);
        if installed.exists() {
            fsx::remove(&kept).map_err(|e| describe("clear", &kept, e))?;
            fsx::rename(&installed, &kept).map_err(|e| describe("move away", &installed, e))?;
        }
        fsx::rename(&incoming, &installed).map_err(|e| describe("move in", &incoming, e))?;
    }

    for name in previous.parts.keys().filter(|name| *name != STUB_PART && !new.parts.contains_key(*name)) {
        let installed = layout.part(name);
        if installed.exists() {
            let kept = layout.old().join(name);
            fsx::remove(&kept).map_err(|e| describe("clear", &kept, e))?;
            fsx::rename(&installed, &kept).map_err(|e| describe("move away", &installed, e))?;
        }
    }

    let new_stub = layout.update().join(STUB_PART);
    if new_stub.is_dir() {
        // A stub that could not be replaced is not worth failing the update for: the old one keeps working.
        if let Err(e) = replace_stub(&new_stub, exe) {
            log!("the stub was not replaced: {e}");
        }
        let _ = fsx::remove(&new_stub);
    }

    if staged.exists() {
        if layout.manifest().exists() {
            fsx::rename(&layout.manifest(), &layout.old_manifest()).map_err(|e| describe("move away", &layout.manifest(), e))?;
        }
        fsx::rename(&staged, &layout.manifest()).map_err(|e| describe("move in", &staged, e))?;
    }

    // READY goes first: should this be interrupted, what is left reads as "a swap to finish", never as "a release to apply".
    let _ = fsx::remove(&layout.ready());
    let _ = fsx::remove(&layout.applying());
    let _ = fsx::remove(&layout.update());
    log!("version {} is in place (was {})", new.version, previous.version);
    Ok(Some(new))
}

/// Puts back whatever a failed swap had moved, leaving the old release complete and the download ready for another try.
fn undo(layout: &Layout) -> Result<(), String> {
    let staged = layout.staged_manifest();
    let (new, previous) = if staged.exists() {
        let previous = if layout.old_manifest().exists() { read(&layout.old_manifest())? } else { read(&layout.manifest())? };
        (read(&staged)?, previous)
    } else {
        (read(&layout.manifest())?, read(&layout.old_manifest())?)
    };

    for name in new.parts.keys().filter(|name| *name != STUB_PART) {
        let incoming = layout.update().join(name);
        let installed = layout.part(name);
        let kept = layout.old().join(name);
        if previous.parts.contains_key(name) {
            if kept.exists() {
                if !incoming.exists() && installed.exists() {
                    fsx::rename(&installed, &incoming).map_err(|e| describe("move back", &installed, e))?;
                }
                fsx::rename(&kept, &installed).map_err(|e| describe("restore", &kept, e))?;
            }
        } else if !incoming.exists() && installed.exists() {
            fsx::rename(&installed, &incoming).map_err(|e| describe("move back", &installed, e))?;
        }
    }
    for name in previous.parts.keys().filter(|name| *name != STUB_PART && !new.parts.contains_key(*name)) {
        let kept = layout.old().join(name);
        if kept.exists() && !layout.part(name).exists() {
            fsx::rename(&kept, &layout.part(name)).map_err(|e| describe("restore", &kept, e))?;
        }
    }
    if layout.old_manifest().exists() {
        if !staged.exists() {
            fsx::rename(&layout.manifest(), &staged).map_err(|e| describe("move back", &layout.manifest(), e))?;
        }
        fsx::rename(&layout.old_manifest(), &layout.manifest()).map_err(|e| describe("restore", &layout.old_manifest(), e))?;
    }
    let _ = fsx::remove(&layout.applying());
    let _ = fsx::remove(&layout.old());
    Ok(())
}

/// Takes out the release that was swapped in last and puts the kept one back. Returns the manifest that is installed again.
pub fn rollback(layout: &Layout) -> Result<Manifest, String> {
    if !layout.old_manifest().exists() {
        if layout.rollback_marker().exists() {
            // Interrupted after the manifest was restored: only the tidying up was left.
            let _ = fsx::remove(&layout.old());
            let _ = fsx::remove(&layout.trash());
            return read(&layout.manifest());
        }
        return Err("there is no earlier version to go back to".into());
    }
    let previous = read(&layout.old_manifest())?;
    let new = read(&layout.manifest())?;
    fsx::mark(&layout.rollback_marker()).map_err(|e| describe("write", &layout.rollback_marker(), e))?;
    fs::create_dir_all(layout.trash()).map_err(|e| describe("create", &layout.trash(), e))?;

    for name in new.parts.keys().filter(|name| *name != STUB_PART) {
        let installed = layout.part(name);
        let kept = layout.old().join(name);
        let dumped = layout.trash().join(name);
        if previous.parts.contains_key(name) {
            if kept.exists() {
                if installed.exists() {
                    fsx::remove(&dumped).map_err(|e| describe("clear", &dumped, e))?;
                    fsx::rename(&installed, &dumped).map_err(|e| describe("move away", &installed, e))?;
                }
                fsx::rename(&kept, &installed).map_err(|e| describe("restore", &kept, e))?;
            }
        } else if installed.exists() {
            fsx::remove(&dumped).map_err(|e| describe("clear", &dumped, e))?;
            fsx::rename(&installed, &dumped).map_err(|e| describe("move away", &installed, e))?;
        }
    }
    for name in previous.parts.keys().filter(|name| *name != STUB_PART && !new.parts.contains_key(*name)) {
        let kept = layout.old().join(name);
        if kept.exists() && !layout.part(name).exists() {
            fsx::rename(&kept, &layout.part(name)).map_err(|e| describe("restore", &kept, e))?;
        }
    }
    let dumped_manifest = layout.trash().join("jpack.json");
    fsx::remove(&dumped_manifest).map_err(|e| describe("clear", &dumped_manifest, e))?;
    fsx::rename(&layout.manifest(), &dumped_manifest).map_err(|e| describe("move away", &layout.manifest(), e))?;
    fsx::rename(&layout.old_manifest(), &layout.manifest()).map_err(|e| describe("restore", &layout.old_manifest(), e))?;

    let _ = fsx::remove(&layout.old());
    let _ = fsx::remove(&layout.trash());
    log!("went back to version {} (version {} did not start)", previous.version, new.version);
    Ok(previous)
}

/// The new release has started: the parts it replaced are deleted.
pub fn confirm(layout: &Layout) {
    if layout.applying().exists() || layout.rollback_marker().exists() {
        return;
    }
    if layout.old().exists() {
        match fsx::remove(&layout.old()) {
            Ok(()) => log!("the new version started; the replaced files are deleted"),
            Err(e) => log!("the replaced files could not be deleted yet: {e}"),
        }
    }
    let _ = fsx::remove(&layout.trash());
}

/// Finishes whatever an interrupted run left behind, then swaps in a waiting release.
pub fn recover_and_apply(layout: &Layout, exe: &Path) -> Result<Option<Manifest>, ApplyError> {
    if layout.rollback_marker().exists() {
        rollback(layout).map_err(ApplyError::Broken)?;
    }
    apply(layout, exe)
}

/// Windows refuses to overwrite a running program but allows renaming it, so the running stub is moved aside and the new
/// one takes its name. The old file is deleted at the next start.
fn replace_stub(new_stub_dir: &Path, exe: &Path) -> Result<(), String> {
    let new_stub = fs::read_dir(new_stub_dir)
        .map_err(|e| e.to_string())?
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .find(|path| path.is_file() && path.file_name().is_some_and(|n| n.to_string_lossy().starts_with("stub")))
        .ok_or("the stub part holds no stub")?;
    let aside = aside_name(exe);
    fsx::remove(&aside).map_err(|e| describe("clear", &aside, e))?;
    fsx::rename(exe, &aside).map_err(|e| describe("move away", exe, e))?;
    if let Err(e) = fsx::rename(&new_stub, exe) {
        let _ = fsx::rename(&aside, exe);
        return Err(describe("move in", &new_stub, e));
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = fs::set_permissions(exe, fs::Permissions::from_mode(0o755));
    }
    log!("the stub was replaced; the new one is used from the next start");
    Ok(())
}

/// Where the running stub is parked while a new one takes its place.
pub fn aside_name(exe: &Path) -> std::path::PathBuf {
    let mut name = exe.as_os_str().to_owned();
    name.push(".old");
    name.into()
}

pub fn read(path: &Path) -> Result<Manifest, String> {
    let bytes = fs::read(path).map_err(|e| format!("cannot read {}: {e}", path.display()))?;
    Manifest::parse(&bytes).map_err(|e| format!("{}: {e}", path.display()))
}

fn discard_staging(layout: &Layout) {
    let _ = fsx::remove(&layout.ready());
    let _ = fsx::remove(&layout.update());
}

fn describe(action: &str, path: &Path, error: std::io::Error) -> String {
    format!("cannot {action} {}: {error}", path.display())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU32, Ordering};

    static COUNTER: AtomicU32 = AtomicU32::new(0);

    /// An install folder with version 1 (parts `app` and `runtime`) and a throwaway file standing in for the stub.
    struct Install {
        layout: Layout,
        exe: PathBuf,
    }

    impl Drop for Install {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.layout.root);
        }
    }

    fn manifest(version: &str, parts: &[(&str, char)]) -> String {
        let parts: Vec<String> = parts
            .iter()
            .map(|(name, digit)| format!(r#""{name}":{{"file":"{name}.zip","sha256":"{}","size":1}}"#, digit.to_string().repeat(64)))
            .collect();
        format!(
            r#"{{"format":1,"name":"Demo","version":"{version}","parts":{{{}}},
               "launch":{{"start":"app","entries":{{"app":{{"classpath":["app/demo.jar"],"mainClass":"demo.Main"}}}}}}}}"#,
            parts.join(",")
        )
    }

    fn put(dir: &Path, content: &str) {
        fs::create_dir_all(dir).unwrap();
        fs::write(dir.join("content"), content).unwrap();
    }

    fn content(dir: PathBuf) -> String {
        fs::read_to_string(dir.join("content")).unwrap_or_else(|_| "<missing>".into())
    }

    fn install() -> Install {
        let root = std::env::temp_dir().join(format!("jpack-test-{}-{}", std::process::id(), COUNTER.fetch_add(1, Ordering::SeqCst)));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(&root).unwrap();
        let layout = Layout::new(&root);
        fs::write(layout.manifest(), manifest("1", &[("app", '1'), ("runtime", '1')])).unwrap();
        put(&layout.part("app"), "app 1");
        put(&layout.part("runtime"), "runtime 1");
        let exe = root.join("demo-stub");
        fs::write(&exe, "stub 1").unwrap();
        Install { layout, exe }
    }

    /// Stages version 2: `changed` parts get new content, the manifest lists `parts`.
    fn stage(install: &Install, parts: &[(&str, char)], changed: &[&str]) {
        let layout = &install.layout;
        fs::create_dir_all(layout.update()).unwrap();
        for name in changed {
            put(&layout.update().join(name), &format!("{name} 2"));
        }
        fs::write(layout.staged_manifest(), manifest("2", parts)).unwrap();
        fsx::mark(&layout.ready()).unwrap();
    }

    fn version(layout: &Layout) -> String {
        read(&layout.manifest()).unwrap().version
    }

    #[test]
    fn without_a_download_nothing_happens() {
        let install = install();
        assert!(apply(&install.layout, &install.exe).unwrap().is_none());
        assert_eq!(version(&install.layout), "1");
    }

    #[test]
    fn a_swap_replaces_only_the_changed_parts_and_keeps_the_old_ones_until_confirmed() {
        let install = install();
        let layout = &install.layout;
        stage(&install, &[("app", '2'), ("runtime", '1')], &["app"]);

        let installed = apply(layout, &install.exe).unwrap().unwrap();

        assert_eq!(installed.version, "2");
        assert_eq!(version(layout), "2");
        assert_eq!(content(layout.part("app")), "app 2");
        assert_eq!(content(layout.part("runtime")), "runtime 1");
        assert!(!layout.update().exists(), "the download folder is gone");
        assert!(layout.on_trial());
        assert_eq!(content(layout.old().join("app")), "app 1");
        assert!(!layout.old().join("runtime").exists(), "an unchanged part is not copied");

        confirm(layout);
        assert!(!layout.old().exists());
        assert!(!layout.on_trial());
    }

    #[test]
    fn a_whole_new_runtime_and_app_are_swapped_together() {
        let install = install();
        let layout = &install.layout;
        stage(&install, &[("app", '2'), ("runtime", '2')], &["app", "runtime"]);

        apply(layout, &install.exe).unwrap().unwrap();

        assert_eq!(content(layout.part("app")), "app 2");
        assert_eq!(content(layout.part("runtime")), "runtime 2");
    }

    #[test]
    fn a_rollback_puts_everything_back() {
        let install = install();
        let layout = &install.layout;
        stage(&install, &[("app", '2'), ("runtime", '2'), ("extras", '2')], &["app", "runtime", "extras"]);
        apply(layout, &install.exe).unwrap().unwrap();
        assert_eq!(content(layout.part("extras")), "extras 2");

        let back = rollback(layout).unwrap();

        assert_eq!(back.version, "1");
        assert_eq!(version(layout), "1");
        assert_eq!(content(layout.part("app")), "app 1");
        assert_eq!(content(layout.part("runtime")), "runtime 1");
        assert!(!layout.part("extras").exists(), "a part the new release added is removed");
        assert!(!layout.old().exists());
        assert!(!layout.trash().exists());
        assert!(!layout.on_trial());
    }

    #[test]
    fn a_part_the_new_release_drops_is_removed_and_comes_back_on_rollback() {
        let install = install();
        let layout = &install.layout;
        stage(&install, &[("app", '2')], &["app"]);

        apply(layout, &install.exe).unwrap().unwrap();
        assert!(!layout.part("runtime").exists());
        assert_eq!(content(layout.old().join("runtime")), "runtime 1");

        rollback(layout).unwrap();
        assert_eq!(content(layout.part("runtime")), "runtime 1");
        assert_eq!(content(layout.part("app")), "app 1");
    }

    #[test]
    fn a_swap_interrupted_after_moving_a_part_away_is_finished_by_the_next_attempt() {
        let install = install();
        let layout = &install.layout;
        stage(&install, &[("app", '2'), ("runtime", '2')], &["app", "runtime"]);
        // What a power cut leaves: the swap had started and `app` was moved away, nothing else.
        fs::create_dir_all(layout.old()).unwrap();
        fsx::mark(&layout.applying()).unwrap();
        fs::rename(layout.part("app"), layout.old().join("app")).unwrap();

        apply(layout, &install.exe).unwrap().unwrap();

        assert_eq!(version(layout), "2");
        assert_eq!(content(layout.part("app")), "app 2");
        assert_eq!(content(layout.part("runtime")), "runtime 2");
        assert_eq!(content(layout.old().join("app")), "app 1");
        assert_eq!(content(layout.old().join("runtime")), "runtime 1");
        assert!(!layout.update().exists());
    }

    #[test]
    fn a_swap_interrupted_between_the_two_manifest_moves_is_finished() {
        let install = install();
        let layout = &install.layout;
        stage(&install, &[("app", '2'), ("runtime", '1')], &["app"]);
        fs::create_dir_all(layout.old()).unwrap();
        fsx::mark(&layout.applying()).unwrap();
        fs::rename(layout.part("app"), layout.old().join("app")).unwrap();
        fs::rename(layout.update().join("app"), layout.part("app")).unwrap();
        fs::rename(layout.manifest(), layout.old_manifest()).unwrap();

        apply(layout, &install.exe).unwrap().unwrap();

        assert_eq!(version(layout), "2");
        assert_eq!(content(layout.part("app")), "app 2");
        assert_eq!(read(&layout.old_manifest()).unwrap().version, "1");
    }

    #[test]
    fn a_swap_interrupted_while_tidying_up_is_finished() {
        let install = install();
        let layout = &install.layout;
        stage(&install, &[("app", '2'), ("runtime", '1')], &["app"]);
        apply(layout, &install.exe).unwrap().unwrap();
        // Put back what the tidying up had not removed yet.
        fs::create_dir_all(layout.update()).unwrap();
        fsx::mark(&layout.applying()).unwrap();

        let installed = apply(layout, &install.exe).unwrap().unwrap();

        assert_eq!(installed.version, "2");
        assert!(!layout.update().exists());
        assert_eq!(content(layout.old().join("app")), "app 1", "the kept version survives the second attempt");
    }

    #[test]
    fn a_swap_that_cannot_finish_is_undone_and_can_be_tried_again() {
        let install = install();
        let layout = &install.layout;
        stage(&install, &[("app", '2'), ("runtime", '2'), ("extras", '2')], &["app", "runtime", "extras"]);

        // `runtime` is the last part: `app` and `extras` are already swapped when moving the old runtime away fails.
        fsx::FAIL_RENAME_TO.with(|f| *f.borrow_mut() = Some(layout.old().join("runtime")));
        let outcome = apply(layout, &install.exe);
        fsx::FAIL_RENAME_TO.with(|f| *f.borrow_mut() = None);

        assert!(matches!(outcome, Err(ApplyError::Deferred(_))), "{outcome:?}");
        assert_eq!(version(layout), "1");
        assert_eq!(content(layout.part("app")), "app 1");
        assert_eq!(content(layout.part("runtime")), "runtime 1");
        assert!(!layout.part("extras").exists());
        assert!(layout.ready().exists(), "the download is kept");
        assert!(!layout.applying().exists());
        assert!(!layout.on_trial());

        apply(layout, &install.exe).unwrap().unwrap();
        assert_eq!(version(layout), "2");
        assert_eq!(content(layout.part("runtime")), "runtime 2");
        assert_eq!(content(layout.part("extras")), "extras 2");
    }

    #[test]
    fn a_swap_that_can_neither_finish_nor_be_undone_is_finished_by_a_later_attempt() {
        let install = install();
        let layout = &install.layout;
        stage(&install, &[("app", '2'), ("runtime", '2')], &["app", "runtime"]);

        // Moving the new runtime in fails, and so does putting the old one back: both target the same place.
        fsx::FAIL_RENAME_TO.with(|f| *f.borrow_mut() = Some(layout.part("runtime")));
        let outcome = apply(layout, &install.exe);
        fsx::FAIL_RENAME_TO.with(|f| *f.borrow_mut() = None);
        assert!(matches!(outcome, Err(ApplyError::Broken(_))), "{outcome:?}");
        assert!(layout.applying().exists(), "the next start knows there is a swap to finish");

        apply(layout, &install.exe).unwrap().unwrap();
        assert_eq!(version(layout), "2");
        assert_eq!(content(layout.part("app")), "app 2");
        assert_eq!(content(layout.part("runtime")), "runtime 2");
    }

    #[test]
    fn an_interrupted_rollback_is_finished_before_anything_else() {
        let install = install();
        let layout = &install.layout;
        stage(&install, &[("app", '2'), ("runtime", '2')], &["app", "runtime"]);
        apply(layout, &install.exe).unwrap().unwrap();
        // A rollback that got as far as restoring `app`.
        fsx::mark(&layout.rollback_marker()).unwrap();
        fs::create_dir_all(layout.trash()).unwrap();
        fs::rename(layout.part("app"), layout.trash().join("app")).unwrap();
        fs::rename(layout.old().join("app"), layout.part("app")).unwrap();

        let installed = recover_and_apply(layout, &install.exe).unwrap();

        assert!(installed.is_none(), "nothing new was swapped in");
        assert_eq!(version(layout), "1");
        assert_eq!(content(layout.part("app")), "app 1");
        assert_eq!(content(layout.part("runtime")), "runtime 1");
        assert!(!layout.old().exists());
    }

    #[test]
    fn a_download_without_a_readable_manifest_is_thrown_away() {
        let install = install();
        let layout = &install.layout;
        stage(&install, &[("app", '2'), ("runtime", '1')], &["app"]);
        fs::write(layout.staged_manifest(), "not json").unwrap();

        assert!(apply(layout, &install.exe).unwrap().is_none());

        assert_eq!(version(layout), "1");
        assert_eq!(content(layout.part("app")), "app 1");
        assert!(!layout.update().exists());
    }

    #[test]
    fn a_new_stub_takes_the_place_of_the_running_one() {
        let install = install();
        let layout = &install.layout;
        stage(&install, &[("app", '1'), ("runtime", '1'), ("stub", '2')], &[]);
        fs::create_dir_all(layout.update().join("stub")).unwrap();
        fs::write(layout.update().join("stub").join("stub.exe"), "stub 2").unwrap();

        apply(layout, &install.exe).unwrap().unwrap();

        assert_eq!(fs::read_to_string(&install.exe).unwrap(), "stub 2");
        assert_eq!(fs::read_to_string(aside_name(&install.exe)).unwrap(), "stub 1");
        assert!(!layout.part("stub").exists(), "the stub is a file, never a folder in the install");

        rollback(layout).unwrap();
        assert_eq!(fs::read_to_string(&install.exe).unwrap(), "stub 2", "a rollback leaves the stub alone");
        assert_eq!(version(layout), "1");
    }
}
