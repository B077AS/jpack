//! The jpack stub: the small native program that sits next to a Java app, starts it, and keeps it up to date.
//!
//! It is the only thing that starts processes. One run looks like this:
//!
//! 1. Finish whatever an interrupted run left, and swap in a downloaded release if one is waiting.
//! 2. Start the entry the manifest names (the app, or its launcher) and wait for it to exit.
//! 3. While it runs, serve its requests (check, download) and, with the `background` policy, look for updates unasked.
//! 4. When it has exited, swap in a release that was downloaded meanwhile, then start what the program asked for
//!    (a launcher asks for the app; an app asks for itself to restart), or exit with the program's exit code.
//!
//! A launcher can also hand over without exiting first (`start`): when no update waits to be swapped in, the app starts
//! next to it, says `shown` once its window is up, and the launcher closes then. The stub's exit code is then the app's.
//!
//! A release that was just swapped in is on trial: if its first start fails at once, the old parts are put back.

#![cfg_attr(windows, windows_subsystem = "windows")]

mod app;
mod delta;
mod apply;
mod appimage;
mod fsx;
mod ipc;
mod launch;
mod layout;
mod lock;
mod log;
mod manifest;
mod update;

use app::{App, Event, Program};
use apply::ApplyError;
use layout::Layout;
use lock::Lock;
use manifest::{Policy, SUPPORTED_FORMAT};
use serde_json::json;
use std::ffi::OsString;

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::Receiver;
use std::sync::Arc;
use std::time::{Duration, Instant};

/// A new release that runs this long, or says hello, or exits normally, has started.
const HEALTH_WINDOW: Duration = Duration::from_secs(10);

/// A chain of "start this next" requests longer than this is a loop.
const MAX_HOPS: u32 = 20;

fn main() {
    std::process::exit(run());
}

fn run() -> i32 {
    let exe = match std::env::current_exe() {
        Ok(exe) => exe,
        Err(e) => return fatal(&format!("The program cannot find out where it is installed: {e}")),
    };
    let Some(root) = exe.parent().map(Path::to_path_buf) else {
        return fatal("The program cannot find out where it is installed.");
    };
    let layout = Layout::new(root);
    // Inside an AppImage the stub runs from a read-only mount: the AppImage file is what gets updated, and the log goes
    // to the user's state folder.
    let app_image: Option<PathBuf> = std::env::var_os("APPIMAGE").map(PathBuf::from).filter(|path| path.is_file());
    match &app_image {
        Some(path) => log::init(&appimage_log(path)),
        None => log::init(&layout.log()),
    }
    // The stub a previous update moved aside. It cannot be deleted while a window started with it is still open.
    let _ = std::fs::remove_file(apply::aside_name(&exe));

    let lock = InstallLock::open(&layout);
    let writable = match &app_image {
        Some(path) => appimage::can_replace(path),
        None => lock.usable() && layout.writable(),
    };
    if !writable {
        log!("the install folder cannot be written to: the app starts, but it will not be updated");
    }

    // (An AppImage's folder is a read-only mount: there is nothing to recover or swap in it.)
    if app_image.is_none() && writable && lock.try_exclusive() {
        match apply::recover_and_apply(&layout, &exe) {
            Ok(_) => {}
            Err(ApplyError::Deferred(e)) => log!("the update is tried again later: {e}"),
            Err(ApplyError::Broken(e)) => {
                return fatal(&format!("An update could not be completed, and the app cannot start until it is.\nStarting it again may fix this; otherwise install it again.\n\n{e}"));
            }
        }
    }
    lock.shared();

    let installed = match apply::read(&layout.manifest()) {
        Ok(manifest) => manifest,
        Err(e) => return fatal(&format!("The installation is damaged. Install the app again.\n\n{e}")),
    };
    if installed.format > SUPPORTED_FORMAT {
        return fatal("The installation is newer than this program understands. Install the app again.");
    }
    log::decide(installed.log);

    let app = Arc::new(App::new(layout.clone(), installed, writable, app_image.clone()));
    let port = match ipc::serve(app.clone()) {
        Ok(port) => Some(port),
        Err(e) => {
            log!("the channel to the app could not be opened: {e}");
            None
        }
    };
    let events = app.take_events();
    start_background_updates(&app);
    check_while_the_launcher_starts(&app);

    let extra: Vec<OsString> = std::env::args_os().skip(1).collect();
    let runs = Arc::new(AtomicU64::new(0));
    let mut entry_name = app.installed().launch.start.clone();
    // An AppImage that was just replaced starts the image anew and says which program to run in it.
    if let Some(wanted) = std::env::var("JPACK_START").ok().filter(|name| app.installed().launch.entries.contains_key(name)) {
        entry_name = wanted;
    }
    let mut rolled_back = false;
    let mut hops = 0;
    let starter = Starter { app: &app, layout: &layout, exe: &exe, app_image: app_image.as_deref(), extra: &extra, port, ids: AtomicU64::new(0) };

    loop {
        let installed = app.installed();
        if !installed.launch.entries.contains_key(&entry_name) {
            return fatal(&format!("The installation does not know how to start '{entry_name}'."));
        }
        let trial = writable && layout.on_trial();
        *app.next.lock().unwrap() = None;
        app.trial.store(trial, Ordering::SeqCst);
        let run = runs.fetch_add(1, Ordering::SeqCst) + 1;

        log!("starting '{entry_name}' of version {}{}", installed.version, if trial { " (first start after an update)" } else { "" });
        let started = Instant::now();
        let mut running: Vec<Program> = Vec::new();
        // The program that was started first (whose health decides a trial) and the one whose exit code becomes the
        // stub's: the last one started, so the app's after a launcher handed over. Each with its exit code.
        let mut first: (u64, i32) = match starter.spawn(&entry_name) {
            Ok((program, _)) => {
                if trial {
                    confirm_when_it_keeps_running(&app, &runs, run);
                }
                let id = program.id;
                running.push(program);
                (id, 0)
            }
            Err(code) => (0, code),
        };
        let mut main = first;
        wait_for_programs(&starter, &events, &mut running, &mut first, &mut main, writable, &mut hops);

        let (first_id, first_code) = first;
        let code = main.1;
        let healthy = first_code == 0 || app.said_hello(first_id) || started.elapsed() >= HEALTH_WINDOW;

        if app.trial.swap(false, Ordering::SeqCst) || (trial && layout.on_trial()) {
            if healthy {
                apply::confirm(&layout);
            } else if !rolled_back && lock.try_exclusive() {
                let outcome = apply::rollback(&layout);
                lock.shared();
                match outcome {
                    Ok(previous) => {
                        app.updater.skip(&installed);
                        entry_name = previous.launch.start.clone();
                        *app.manifest.lock().unwrap() = previous;
                        rolled_back = true;
                        continue;
                    }
                    Err(e) => log!("going back to the earlier version failed: {e}"),
                }
            } else {
                lock.shared();
            }
        }

        let next = app.next.lock().unwrap().take();
        if writable && (layout.ready().exists() || layout.applying().exists()) {
            if lock.try_exclusive() {
                match apply::apply(&layout, &exe) {
                    Ok(Some(new)) => *app.manifest.lock().unwrap() = new,
                    Ok(None) => {}
                    Err(ApplyError::Deferred(e)) => log!("the update is tried again later: {e}"),
                    Err(ApplyError::Broken(e)) => {
                        return fatal(&format!("An update could not be completed.\nStarting the app again may fix this; otherwise install it again.\n\n{e}"));
                    }
                }
            } else {
                log!("another window of the app is open: the update waits until all are closed");
            }
            lock.shared();
        }

        // The AppImage file was replaced while this one ran: what comes next runs from the new file.
        if let (Some(path), Some(version)) = (&app_image, app.updated.lock().unwrap().clone()) {
            let Some(name) = next else { return code };
            log!("starting '{name}' from the updated AppImage (version {version})");
            return match std::process::Command::new(path).args(&extra).env("JPACK_START", &name).status() {
                Ok(status) => exit_code(status),
                Err(e) => fatal(&format!("The updated app could not be started: {e}")),
            };
        }

        match next {
            None => return code,
            Some(name) => {
                hops += 1;
                if hops > MAX_HOPS {
                    log!("too many restarts in a row: stopping");
                    return code;
                }
                if !app.installed().launch.entries.contains_key(&name) {
                    return fatal(&format!("The app asked to start '{name}', which this version does not have."));
                }
                entry_name = name;
            }
        }
    }
}

/// Starts programs: the one the loop begins with, and the ones a running program asks for with `start`.
struct Starter<'a> {
    app: &'a Arc<App>,
    layout: &'a Layout,
    exe: &'a Path,
    app_image: Option<&'a Path>,
    extra: &'a [OsString],
    port: Option<u16>,
    ids: AtomicU64,
}

impl Starter<'_> {
    /// Starts an entry and a thread that reports its exit to the loop. On failure, the exit code to report.
    fn spawn(&self, entry_name: &str) -> Result<(Program, u32), i32> {
        let installed = self.app.installed();
        let Some(entry) = installed.launch.entries.get(entry_name) else {
            log!("'{entry_name}' could not be started: this version does not have it");
            return Err(126);
        };
        let mut command = match launch::command(&self.layout.root, entry, self.extra) {
            Ok(command) => command,
            Err(e) => {
                log!("'{entry_name}' could not be started: {e}");
                return Err(126);
            }
        };
        // In an AppImage, links and shortcuts must point at the AppImage file, not at the stub inside its mount.
        let program = self.app_image.unwrap_or(self.exe);
        command.env("JPACK_HOME", &self.layout.root).env("JPACK_EXE", program).env("JPACK_NAME", &installed.name).env("JPACK_VERSION", &installed.version).env("JPACK_ENTRY", entry_name);
        let id = self.ids.fetch_add(1, Ordering::SeqCst) + 1;
        let started = Program { id, entry: entry_name.to_string() };
        if let Some(port) = self.port {
            // A token per program: the stub knows who is talking, and a program that has exited cannot come back.
            let token = ipc::new_token();
            command.env("JPACK_PORT", port.to_string()).env("JPACK_TOKEN", &token);
            self.app.register(token, started.clone());
        }
        match command.spawn() {
            Ok(mut child) => {
                let pid = child.id();
                let app = self.app.clone();
                let name = entry_name.to_string();
                std::thread::spawn(move || {
                    let code = match child.wait() {
                        Ok(status) => exit_code(status),
                        Err(e) => {
                            log!("waiting for '{name}' failed: {e}");
                            1
                        }
                    };
                    app.send(Event::Exited { id, code });
                });
                Ok((started, pid))
            }
            Err(e) => {
                self.app.unregister(id);
                log!("'{entry_name}' could not be started: {e}");
                Err(126)
            }
        }
    }

    /// True when a downloaded release waits to be swapped in, which needs every program to have exited first.
    fn update_waiting(&self, writable: bool) -> bool {
        (writable && (self.layout.ready().exists() || self.layout.applying().exists())) || self.app.updated.lock().unwrap().is_some()
    }

    /// A running program (`from`) asks for `entry` to start now. See [`Event::Start`].
    fn start_now(&self, entry: &str, from: u64, running: &mut Vec<Program>, main: &mut (u64, i32), writable: bool, hops: &mut u32) -> Result<Option<u32>, String> {
        let Some(asking) = running.iter().find(|program| program.id == from).cloned() else {
            return Err("the program that asked has ended".into());
        };
        if !self.app.installed().launch.entries.contains_key(entry) {
            return Err(format!("this version has no entry '{entry}'"));
        }
        if running.len() > 1 {
            return Err("another program is already running next to this one".into());
        }
        if *hops >= MAX_HOPS {
            return Err("too many starts in a row".into());
        }
        *hops += 1;
        if self.update_waiting(writable) {
            log!("'{entry}' starts once '{}' has exited and the update is swapped in", asking.entry);
            *self.app.next.lock().unwrap() = Some(entry.to_string());
            return Ok(None);
        }
        log!("starting '{entry}' next to '{}'", asking.entry);
        match self.spawn(entry) {
            Ok((program, pid)) => {
                *main = (program.id, 0);
                running.push(program);
                Ok(Some(pid))
            }
            Err(_) => Err(format!("'{entry}' could not be started; the reason is in the log")),
        }
    }
}

/// Waits until every running program has exited, starting the ones they ask for in between. Records the exit codes of
/// `first` and `main`, and tells the programs still connected about each exit.
fn wait_for_programs(starter: &Starter, events: &Receiver<Event>, running: &mut Vec<Program>, first: &mut (u64, i32), main: &mut (u64, i32), writable: bool, hops: &mut u32) {
    while !running.is_empty() {
        let Ok(event) = events.recv() else { return };
        match event {
            Event::Exited { id, code } => {
                let Some(index) = running.iter().position(|program| program.id == id) else { continue };
                let program = running.remove(index);
                starter.app.unregister(id);
                log!("'{}' ended with code {code}", program.entry);
                for slot in [&mut *first, &mut *main] {
                    if slot.0 == id {
                        slot.1 = code;
                    }
                }
                starter.app.hub.broadcast(&json!({"event": "exited", "entry": program.entry, "code": code, "shown": starter.app.was_shown(id)}));
            }
            Event::Start { entry, from, reply } => {
                let answer = starter.start_now(&entry, from, running, main, writable, hops);
                if let Err(e) = &answer {
                    log!("'{entry}' was not started: {e}");
                }
                let _ = reply.send(answer);
            }
        }
    }
}

/// A launcher's first question is whether there is an update. With the `manual` policy and a launcher as the start entry,
/// the stub asks the update address while the launcher's Java is still starting, and the launcher's `check` gets that
/// answer at once instead of waiting for the network.
fn check_while_the_launcher_starts(app: &Arc<App>) {
    let installed = app.installed();
    let Some(config) = &installed.update else { return };
    let starts_a_launcher = installed.launch.start != "app" && installed.launch.entries.contains_key("app");
    if config.policy != Policy::Manual || !app.writable || !starts_a_launcher || std::env::var_os("JPACK_START").is_some() {
        return;
    }
    let app = app.clone();
    std::thread::spawn(move || match app.check() {
        Ok(check) if check.available => log!("version {} is published", check.version),
        Ok(_) => {}
        Err(e) => log!("looking for an update failed: {e}"),
    });
}

/// With the `background` policy the stub looks for updates by itself: shortly after the app has started, and then every
/// few hours for as long as it stays open. What it downloads is swapped in when the app is closed.
fn start_background_updates(app: &Arc<App>) {
    let installed = app.installed();
    let Some(config) = installed.update else { return };
    if config.policy != Policy::Background || !app.writable {
        return;
    }
    let delay = env_millis("JPACK_CHECK_DELAY_MS").unwrap_or(Duration::from_secs(5));
    let interval = env_millis("JPACK_CHECK_INTERVAL_MS").unwrap_or(Duration::from_secs(u64::from(config.interval_hours.unwrap_or(4).max(1)) * 3600));
    let app = app.clone();
    std::thread::spawn(move || {
        std::thread::sleep(delay);
        loop {
            match app.download() {
                Ok(Some(version)) => log!("version {version} will be used after the app is closed"),
                Ok(None) => {}
                Err(e) => log!("looking for an update failed: {e}"),
            }
            std::thread::sleep(interval);
        }
    });
}

/// A new release that is still running after the health window has started fine: the replaced files can go, without
/// waiting for the user to close the app.
fn confirm_when_it_keeps_running(app: &Arc<App>, runs: &Arc<AtomicU64>, run: u64) {
    let app = app.clone();
    let runs = runs.clone();
    std::thread::spawn(move || {
        std::thread::sleep(HEALTH_WINDOW);
        if runs.load(Ordering::SeqCst) == run && app.trial.swap(false, Ordering::SeqCst) {
            apply::confirm(&app.layout);
        }
    });
}

fn env_millis(name: &str) -> Option<Duration> {
    std::env::var(name).ok()?.trim().parse().ok().map(Duration::from_millis)
}

fn exit_code(status: std::process::ExitStatus) -> i32 {
    if let Some(code) = status.code() {
        return code;
    }
    #[cfg(unix)]
    {
        use std::os::unix::process::ExitStatusExt;
        if let Some(signal) = status.signal() {
            return 128 + signal;
        }
    }
    1
}

/// The lock that keeps file swaps and running programs apart. Every stub holds it shared while its program runs; a swap
/// needs it exclusively, so it only happens when no window of the app is open.
struct InstallLock {
    lock: Option<Lock>,
}

impl InstallLock {
    fn open(layout: &Layout) -> InstallLock {
        InstallLock { lock: Lock::install(layout).ok() }
    }

    fn usable(&self) -> bool {
        self.lock.is_some()
    }

    /// Gives up the shared lock and tries for the exclusive one. On `false` the caller holds nothing.
    fn try_exclusive(&self) -> bool {
        self.lock.as_ref().is_some_and(Lock::try_exclusive)
    }

    /// Takes the shared lock, waiting while another stub swaps files.
    fn shared(&self) {
        if let Some(Err(e)) = self.lock.as_ref().map(Lock::shared) {
            log!("the install lock could not be taken: {e}");
        }
    }
}

/// Reports a problem the stub cannot work around and returns the exit code for it.
fn fatal(message: &str) -> i32 {
    log::force();
    log!("fatal: {message}");
    eprintln_quiet(message);
    #[cfg(windows)]
    if std::env::var_os("JPACK_NO_DIALOG").is_none() {
        message_box(message);
    }
    1
}

fn eprintln_quiet(message: &str) {
    use std::io::Write;
    let _ = writeln!(std::io::stderr(), "{message}");
}

/// The stub has no console on Windows, so a problem that stops the app from starting is shown in a message box.
#[cfg(windows)]
fn message_box(message: &str) {
    use std::os::windows::ffi::OsStrExt;
    #[link(name = "user32")]
    extern "system" {
        fn MessageBoxW(window: *mut std::ffi::c_void, text: *const u16, caption: *const u16, kind: u32) -> i32;
    }
    let wide = |text: &str| -> Vec<u16> { std::ffi::OsStr::new(text).encode_wide().chain(std::iter::once(0)).collect() };
    let caption = std::env::current_exe().ok().and_then(|exe| exe.file_stem().map(|s| s.to_string_lossy().into_owned())).unwrap_or_else(|| "jpack".into());
    let (text, caption) = (wide(message), wide(&caption));
    const MB_ICONERROR: u32 = 0x10;
    unsafe {
        MessageBoxW(std::ptr::null_mut(), text.as_ptr(), caption.as_ptr(), MB_ICONERROR);
    }
}

/// The log of an AppImage run: the mount is read-only, so it goes to the user's state folder, one file per AppImage.
fn appimage_log(appimage: &Path) -> PathBuf {
    let base = std::env::var_os("XDG_STATE_HOME")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|home| PathBuf::from(home).join(".local").join("state")))
        .unwrap_or_else(std::env::temp_dir);
    let dir = base.join("jpack");
    let _ = std::fs::create_dir_all(&dir);
    dir.join(format!("{}.log", appimage.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| "app".into())))
}
