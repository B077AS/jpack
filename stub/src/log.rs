//! A small log file next to the stub. The stub has no console on Windows, so this is the only place it can explain itself.

use std::fs::{File, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

const MAX_BYTES: u64 = 512 * 1024;

/// Lines kept in memory while it is not yet known whether the log is wanted.
const MAX_WAITING: usize = 500;

/// Whether the log is wanted is a setting of the installed manifest, which is only read some way into the start. Until then
/// the lines wait in memory: they are written when the log turns out to be wanted (or something fatal happens), else dropped.
enum State {
    Waiting(Vec<String>),
    On(File),
    Off,
}

static STATE: Mutex<State> = Mutex::new(State::Waiting(Vec::new()));
static PATH: OnceLock<PathBuf> = OnceLock::new();
static ECHO: OnceLock<bool> = OnceLock::new();

/// Says where the log goes. Nothing is created until [`decide`] (or [`force`]) says so.
pub fn init(path: &Path) {
    let _ = PATH.set(path.to_path_buf());
    let _ = ECHO.set(std::env::var_os("JPACK_DEBUG").is_some());
}

/// The manifest's `log` setting is known: write the log (starting it again from empty once it has grown large) or not.
pub fn decide(wanted: bool) {
    if wanted {
        force();
    } else if let Ok(mut state) = STATE.lock() {
        if matches!(*state, State::Waiting(_)) {
            *state = State::Off;
        }
    }
}

/// Writes the log whatever the setting says, for a failure that stops the app: it must be possible to find out why.
pub fn force() {
    let Ok(mut state) = STATE.lock() else { return };
    let lines = match &*state {
        State::On(_) => return,
        State::Waiting(lines) => lines.concat(),
        State::Off => String::new(),
    };
    let file = PATH.get().and_then(|path| {
        let too_big = std::fs::metadata(path).map(|m| m.len() > MAX_BYTES).unwrap_or(false);
        OpenOptions::new().create(true).append(!too_big).write(true).truncate(too_big).open(path).ok()
    });
    *state = match file {
        Some(mut file) => {
            let _ = file.write_all(lines.as_bytes());
            State::On(file)
        }
        // Without a writable folder nothing is logged.
        None => State::Off,
    };
}

pub fn write(message: &str) {
    let line = format!("{} [{}] {}\n", timestamp(), std::process::id(), message);
    if let Ok(mut state) = STATE.lock() {
        match &mut *state {
            State::Waiting(lines) => {
                if lines.len() < MAX_WAITING {
                    lines.push(line.clone());
                }
            }
            State::On(file) => {
                let _ = file.write_all(line.as_bytes());
            }
            State::Off => {}
        }
    }
    if *ECHO.get().unwrap_or(&false) {
        let _ = std::io::stderr().write_all(format!("jpack: {line}").as_bytes());
    }
}

#[macro_export]
macro_rules! log {
    ($($arg:tt)*) => { $crate::log::write(&format!($($arg)*)) };
}

/// UTC, `2026-11-10T21:04:05Z`.
fn timestamp() -> String {
    let seconds = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    let (year, month, day) = civil_from_days((seconds / 86_400) as i64);
    let rest = seconds % 86_400;
    format!("{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}Z", rest / 3600, rest % 3600 / 60, rest % 60)
}

/// Days since 1970-01-01 to a calendar date (Howard Hinnant's algorithm).
fn civil_from_days(days: i64) -> (i64, i64, i64) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    (yoe + era * 400 + i64::from(month <= 2), month, day)
}

#[cfg(test)]
mod tests {
    use super::civil_from_days;

    #[test]
    fn days_become_calendar_dates() {
        assert_eq!(civil_from_days(0), (1970, 1, 1));
        assert_eq!(civil_from_days(19_782), (2024, 2, 29));
        assert_eq!(civil_from_days(20_767), (2026, 11, 10));
    }
}
