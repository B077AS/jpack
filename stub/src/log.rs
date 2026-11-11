//! A small log file next to the stub. The stub has no console on Windows, so this is the only place it can explain itself.

use std::fs::{File, OpenOptions};
use std::io::Write;
use std::path::Path;
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

const MAX_BYTES: u64 = 512 * 1024;

static FILE: OnceLock<Mutex<Option<File>>> = OnceLock::new();
static ECHO: OnceLock<bool> = OnceLock::new();

/// Opens the log (starting it again from empty once it has grown large). Without a writable folder nothing is logged.
pub fn init(path: &Path) {
    let too_big = std::fs::metadata(path).map(|m| m.len() > MAX_BYTES).unwrap_or(false);
    let file = OpenOptions::new().create(true).append(!too_big).write(true).truncate(too_big).open(path).ok();
    let _ = FILE.set(Mutex::new(file));
    let _ = ECHO.set(std::env::var_os("JPACK_DEBUG").is_some());
}

pub fn write(message: &str) {
    let line = format!("{} [{}] {}\n", timestamp(), std::process::id(), message);
    if let Some(lock) = FILE.get() {
        if let Ok(mut guard) = lock.lock() {
            if let Some(file) = guard.as_mut() {
                let _ = file.write_all(line.as_bytes());
            }
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
