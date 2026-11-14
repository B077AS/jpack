//! What one running stub knows and shares between its threads.

use crate::apply;
use crate::ipc::Hub;
use crate::layout::Layout;
use crate::log;
use crate::manifest::{Manifest, Policy};
use crate::update::{Check, Updater};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// How long the answer of a look at the update address is reused. Long enough for a launcher that is still starting
/// when the stub looks for it; short enough that an app asking again later gets a fresh answer.
const CHECK_REUSE: Duration = Duration::from_secs(60);

/// A program this stub started, as its connections know it: each one gets its own token, which says who is talking.
#[derive(Clone, Debug, PartialEq)]
pub struct Program {
    /// Counts up from 1 for every program this stub starts.
    pub id: u64,
    /// The launch entry it was started as.
    pub entry: String,
}

/// What the start loop waits for while programs run.
pub enum Event {
    /// A program has exited.
    Exited { id: u64, code: i32 },
    /// A running program (`from`) asks for `entry` to be started now, next to it. The answer is the new program's
    /// process id, or `None` when it will start after the running programs have exited (an update has to be swapped in
    /// first).
    Start { entry: String, from: u64, reply: Sender<Result<Option<u32>, String>> },
}

/// The last look at the update address, for [`CHECK_REUSE`].
struct CachedCheck {
    /// The installed release it was made for: after a swap it no longer applies.
    fingerprint: String,
    at: Instant,
    result: Result<Check, String>,
}

pub struct App {
    pub layout: Layout,
    /// What is installed right now. Replaced when a release is swapped in between two programs.
    pub manifest: Mutex<Manifest>,
    /// False when the install folder cannot be written to: the app still starts, it just is not updated.
    pub writable: bool,
    pub updater: Updater,
    pub hub: Hub,
    /// The entry a running program asked to be started after every program has exited.
    pub next: Mutex<Option<String>>,
    /// Set while the running program is the first start of a freshly swapped-in release.
    pub trial: AtomicBool,
    /// One check or download at a time, whoever asks (the app, its launcher, the background thread).
    work: Mutex<()>,
    /// Running from an AppImage: the file that is replaced to update (the install folder is a read-only mount).
    pub app_image: Option<PathBuf>,
    /// The version the AppImage file has been replaced with while this program keeps running from the old one.
    pub updated: Mutex<Option<String>>,
    /// The programs that run now, by their token.
    programs: Mutex<HashMap<String, Program>>,
    /// Programs that have connected, which shows that their Java code started.
    hellos: Mutex<HashSet<u64>>,
    /// Programs that said their window is on screen.
    shown: Mutex<HashSet<u64>>,
    events: Sender<Event>,
    receiver: Mutex<Option<Receiver<Event>>>,
    cached: Mutex<Option<CachedCheck>>,
}

impl App {
    pub fn new(layout: Layout, manifest: Manifest, writable: bool, app_image: Option<PathBuf>) -> App {
        let (events, receiver) = channel();
        App {
            updater: Updater::new(layout.clone()),
            layout,
            app_image,
            updated: Mutex::new(None),
            manifest: Mutex::new(manifest),
            writable,
            hub: Hub::default(),
            next: Mutex::new(None),
            trial: AtomicBool::new(false),
            work: Mutex::new(()),
            programs: Mutex::new(HashMap::new()),
            hellos: Mutex::new(HashSet::new()),
            shown: Mutex::new(HashSet::new()),
            events,
            receiver: Mutex::new(Some(receiver)),
            cached: Mutex::new(None),
        }
    }

    pub fn installed(&self) -> Manifest {
        self.manifest.lock().unwrap().clone()
    }

    /// Looks at the update address. An answer from the last minute (for this installed release) is given again
    /// instead of asking a second time, so a launcher gets at once what the stub looked up while it was starting.
    pub fn check(&self) -> Result<Check, String> {
        let _one_at_a_time = self.work.lock().unwrap();
        self.check_locked(&self.installed())
    }

    fn check_locked(&self, installed: &Manifest) -> Result<Check, String> {
        let fingerprint = installed.fingerprint();
        if let Some(cached) = self.cached.lock().unwrap().as_ref() {
            if cached.fingerprint == fingerprint && cached.at.elapsed() < CHECK_REUSE {
                return cached.result.clone();
            }
        }
        let result = self.updater.check(installed);
        *self.cached.lock().unwrap() = Some(CachedCheck { fingerprint, at: Instant::now(), result: result.clone() });
        result
    }

    /// Forgets the last look: what it said about a download no longer holds.
    fn forget_check(&self) {
        *self.cached.lock().unwrap() = None;
    }

    /// Looks for a newer release and downloads it. Returns its version, or `None` when the installed one is the newest.
    pub fn download(&self) -> Result<Option<String>, String> {
        let _one_at_a_time = self.work.lock().unwrap();
        if !self.writable {
            return Err("the install folder cannot be written to, so the app cannot update itself".into());
        }
        let installed = self.installed();
        let check = self.check_locked(&installed)?;
        if !check.available {
            return Ok(None);
        }
        self.forget_check();
        if let Some(path) = &self.app_image {
            return self.download_app_image(path, &check);
        }
        if !check.staged {
            log!("downloading version {} ({} bytes)", check.version, check.size);
            let mut last_sent: Option<Instant> = None;
            self.updater.stage(&installed, &check, &mut |received, total| {
                // Ten events a second are plenty for a progress bar; the last one is always sent.
                let due = last_sent.is_none_or(|at| at.elapsed() >= Duration::from_millis(100));
                if due || received >= total {
                    last_sent = Some(Instant::now());
                    self.hub.broadcast(&json!({"event": "progress", "received": received, "total": total}));
                }
            })?;
        }
        self.hub.broadcast(&json!({"event": "ready", "version": check.version}));
        Ok(Some(check.version))
    }

    /// AppImage: replaces the AppImage file with the release's. The running program keeps its old mount; the next start of the
    /// file is the new version.
    fn download_app_image(&self, path: &Path, check: &Check) -> Result<Option<String>, String> {
        if self.updated.lock().unwrap().as_deref() == Some(check.version.as_str()) {
            self.hub.broadcast(&json!({"event": "ready", "version": check.version}));
            return Ok(Some(check.version.clone()));
        }
        let (release, url) = check.found().ok_or("there is nothing to download")?;
        let image = release.app_image.clone().ok_or("this release has no AppImage to update to")?;
        log!("updating the AppImage to version {}", check.version);
        let mut last_sent: Option<Instant> = None;
        self.updater.update_app_image(&image, url, path, &mut |received, total| {
            let due = last_sent.is_none_or(|at| at.elapsed() >= Duration::from_millis(100));
            if due || received >= total {
                last_sent = Some(Instant::now());
                self.hub.broadcast(&json!({"event": "progress", "received": received, "total": total}));
            }
        })?;
        *self.updated.lock().unwrap() = Some(check.version.clone());
        self.hub.broadcast(&json!({"event": "ready", "version": check.version}));
        Ok(Some(check.version.clone()))
    }

    /// Records a program the stub is about to start, under the token it will present.
    pub fn register(&self, token: String, program: Program) {
        self.programs.lock().unwrap().insert(token, program);
    }

    /// Forgets a program that has exited: its token opens nothing any more.
    pub fn unregister(&self, id: u64) {
        self.programs.lock().unwrap().retain(|_, program| program.id != id);
    }

    /// The running program a token belongs to.
    pub fn program(&self, token: &str) -> Option<Program> {
        self.programs.lock().unwrap().get(token).cloned()
    }

    /// A program has connected. For the first start of a new release that is the proof that it works.
    pub fn on_hello(&self, id: u64) {
        self.hellos.lock().unwrap().insert(id);
        if self.trial.swap(false, Ordering::SeqCst) {
            apply::confirm(&self.layout);
        }
    }

    pub fn said_hello(&self, id: u64) -> bool {
        self.hellos.lock().unwrap().contains(&id)
    }

    /// A program's window is on screen. Everyone connected hears of it: a launcher waiting to close, above all.
    pub fn on_shown(&self, program: &Program) {
        let first = self.shown.lock().unwrap().insert(program.id);
        self.on_hello(program.id);
        if first {
            log!("'{}' is on screen", program.entry);
        }
        self.hub.broadcast(&json!({"event": "shown", "entry": program.entry}));
    }

    pub fn was_shown(&self, id: u64) -> bool {
        self.shown.lock().unwrap().contains(&id)
    }

    /// Queues an event for the start loop.
    pub fn send(&self, event: Event) {
        let _ = self.events.send(event);
    }

    /// The start loop's end of the event queue. Can be taken once.
    pub fn take_events(&self) -> Receiver<Event> {
        self.receiver.lock().unwrap().take().expect("the events are read by one loop only")
    }

    /// What a program learns when it connects.
    pub fn describe(&self, entry: &str) -> Value {
        let installed = self.installed();
        let policy = installed.update.as_ref().map(|u| u.policy);
        json!({
            "name": installed.name,
            "version": installed.version,
            "entry": entry,
            "stub": env!("CARGO_PKG_VERSION"),
            "staged": self.updated.lock().unwrap().clone().or_else(|| self.updater.staged().map(|m| m.version)),
            "canUpdate": self.writable && installed.update.is_some(),
            "policy": match policy { Some(Policy::Background) => "background", Some(Policy::Manual) => "manual", None => "none" },
            // What this stub understands beyond the first protocol, so a newer library can tell an older stub apart.
            "features": ["start", "shown"],
        })
    }
}
