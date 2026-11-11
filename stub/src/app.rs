//! What one running stub knows and shares between its threads.

use crate::apply;
use crate::ipc::Hub;
use crate::layout::Layout;
use crate::log;
use crate::manifest::{Manifest, Policy};
use crate::update::{Check, Updater};
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

pub struct App {
    pub layout: Layout,
    /// What is installed right now. Replaced when a release is swapped in between two programs.
    pub manifest: Mutex<Manifest>,
    /// False when the install folder cannot be written to: the app still starts, it just is not updated.
    pub writable: bool,
    pub updater: Updater,
    pub hub: Hub,
    /// The entry the running program asked to be started after it exits.
    pub next: Mutex<Option<String>>,
    /// The name of the entry that is running.
    pub entry: Mutex<String>,
    /// Set when the running program has connected, which shows that its Java code started.
    pub hello: AtomicBool,
    /// Set while the running program is the first start of a freshly swapped-in release.
    pub trial: AtomicBool,
    /// One check or download at a time, whoever asks (the app, its launcher, the background thread).
    work: Mutex<()>,
    /// Running from an AppImage: the file that is replaced to update (the install folder is a read-only mount).
    pub app_image: Option<PathBuf>,
    /// The version the AppImage file has been replaced with while this program keeps running from the old one.
    pub updated: Mutex<Option<String>>,
}

impl App {
    pub fn new(layout: Layout, manifest: Manifest, writable: bool, app_image: Option<PathBuf>) -> App {
        App {
            updater: Updater::new(layout.clone()),
            layout,
            app_image,
            updated: Mutex::new(None),
            manifest: Mutex::new(manifest),
            writable,
            hub: Hub::default(),
            next: Mutex::new(None),
            entry: Mutex::new(String::new()),
            hello: AtomicBool::new(false),
            trial: AtomicBool::new(false),
            work: Mutex::new(()),
        }
    }

    pub fn installed(&self) -> Manifest {
        self.manifest.lock().unwrap().clone()
    }

    pub fn check(&self) -> Result<Check, String> {
        let _one_at_a_time = self.work.lock().unwrap();
        self.updater.check(&self.installed())
    }

    /// Looks for a newer release and downloads it. Returns its version, or `None` when the installed one is the newest.
    pub fn download(&self) -> Result<Option<String>, String> {
        let _one_at_a_time = self.work.lock().unwrap();
        if !self.writable {
            return Err("the install folder cannot be written to, so the app cannot update itself".into());
        }
        let installed = self.installed();
        let check = self.updater.check(&installed)?;
        if !check.available {
            return Ok(None);
        }
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

    /// The running program has connected. For the first start of a new release that is the proof that it works.
    pub fn on_hello(&self) {
        self.hello.store(true, Ordering::SeqCst);
        if self.trial.swap(false, Ordering::SeqCst) {
            apply::confirm(&self.layout);
        }
    }

    /// What a program learns when it connects.
    pub fn describe(&self) -> Value {
        let installed = self.installed();
        let policy = installed.update.as_ref().map(|u| u.policy);
        json!({
            "name": installed.name,
            "version": installed.version,
            "entry": *self.entry.lock().unwrap(),
            "stub": env!("CARGO_PKG_VERSION"),
            "staged": self.updated.lock().unwrap().clone().or_else(|| self.updater.staged().map(|m| m.version)),
            "canUpdate": self.writable && installed.update.is_some(),
            "policy": match policy { Some(Policy::Background) => "background", Some(Policy::Manual) => "manual", None => "none" },
        })
    }
}
