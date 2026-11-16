//! Turning a launch entry of the manifest into a Java command line.

use crate::layout::resolve;
use crate::manifest::Entry;
use std::ffi::OsString;
use std::path::{Path, PathBuf};
use std::process::Command;

/// The command that starts `entry`. `extra` are the arguments the stub itself was started with (a file to open, an
/// invite link, ...): they go to the program after the entry's own arguments.
pub fn command(root: &Path, entry: &Entry, extra: &[OsString]) -> Result<Command, String> {
    let java = if entry.uses_system_java() && entry.java.is_none() {
        system_java(java_name(entry), std::env::var_os("JAVA_HOME"), std::env::var_os("PATH")).ok_or_else(|| {
            format!(
                "no Java was found: this app runs on the Java installed on the machine. Install Java, then set JAVA_HOME or put {} on the PATH",
                java_name(entry)
            )
        })?
    } else {
        let java = java_executable(root, entry);
        if !java.is_file() {
            return Err(format!("the Java runtime is missing: {} does not exist", java.display()));
        }
        java
    };
    let home = root.to_string_lossy().into_owned();
    let fill = |text: &str| text.replace("${home}", &home);

    let mut command = Command::new(&java);
    for arg in &entry.jvm_args {
        command.arg(fill(arg));
    }
    if !entry.module_path.is_empty() {
        command.arg("--module-path").arg(join(root, &entry.module_path));
    }
    if !entry.classpath.is_empty() {
        command.arg("-cp").arg(join(root, &entry.classpath));
    }
    match (&entry.main_module, &entry.main_class) {
        (Some(module), Some(class)) => command.arg("-m").arg(format!("{module}/{class}")),
        (Some(module), None) => command.arg("-m").arg(module),
        (None, Some(class)) => command.arg(class),
        (None, None) => return Err("the entry has neither a main class nor a main module".into()),
    };
    for arg in &entry.args {
        command.arg(fill(arg));
    }
    command.args(extra);
    for (key, value) in &entry.env {
        command.env(key, fill(value));
    }
    Ok(command)
}

fn java_executable(root: &Path, entry: &Entry) -> PathBuf {
    if let Some(java) = &entry.java {
        return resolve(root, java);
    }
    resolve(root, &entry.runtime).join("bin").join(java_name(entry))
}

/// The file name of the Java launcher an entry starts with.
fn java_name(entry: &Entry) -> &'static str {
    if cfg!(windows) {
        if entry.console {
            "java.exe"
        } else {
            "javaw.exe"
        }
    } else {
        "java"
    }
}

/// The machine's Java: `<JAVA_HOME>/bin/<name>` when `JAVA_HOME` names a Java, else the first `<name>` on the `PATH`.
fn system_java(name: &str, java_home: Option<OsString>, path: Option<OsString>) -> Option<PathBuf> {
    if let Some(home) = java_home.filter(|home| !home.is_empty()) {
        let java = PathBuf::from(home).join("bin").join(name);
        if java.is_file() {
            return Some(java);
        }
    }
    std::env::split_paths(&path?).map(|dir| dir.join(name)).find(|java| java.is_file())
}

/// Joins paths the way Java expects them on this system. A trailing `*` (all jars of a folder) is kept as it is.
fn join(root: &Path, paths: &[String]) -> OsString {
    let separator = if cfg!(windows) { ";" } else { ":" };
    let mut joined = OsString::new();
    for (index, path) in paths.iter().enumerate() {
        if index > 0 {
            joined.push(separator);
        }
        joined.push(resolve(root, path));
    }
    joined
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::manifest::Manifest;
    use std::fs;

    fn entry(json: &str) -> Entry {
        let text = format!(r#"{{"format":1,"version":"1","launch":{{"start":"app","entries":{{"app":{json}}}}}}}"#);
        Manifest::parse(text.as_bytes()).unwrap().launch.entries.remove("app").unwrap()
    }

    /// A folder that looks enough like a runtime: it has the Java executable the command will name.
    fn root() -> PathBuf {
        let root = std::env::temp_dir().join(format!("jpack-launch-{}-{:?}", std::process::id(), std::thread::current().id()));
        let bin = root.join("runtime").join("bin");
        fs::create_dir_all(&bin).unwrap();
        for name in ["java", "java.exe", "javaw.exe"] {
            fs::write(bin.join(name), "").unwrap();
        }
        root
    }

    fn args(command: &Command) -> Vec<String> {
        command.get_args().map(|a| a.to_string_lossy().into_owned()).collect()
    }

    #[test]
    fn a_classpath_entry_becomes_a_java_command() {
        let root = root();
        let entry = entry(r#"{"jvmArgs":["-Xmx64m","-Dhome=${home}"],"classpath":["app/demo.jar","app/lib/*"],"mainClass":"demo.Main","args":["--flag"]}"#);

        let command = command(&root, &entry, &[OsString::from("file.txt")]).unwrap();

        let separator = if cfg!(windows) { ";" } else { ":" };
        let classpath = format!("{}{separator}{}", root.join("app").join("demo.jar").display(), root.join("app").join("lib").join("*").display());
        assert_eq!(args(&command), vec!["-Xmx64m".to_string(), format!("-Dhome={}", root.display()), "-cp".into(), classpath, "demo.Main".into(), "--flag".into(), "file.txt".into()]);
        let expected = if cfg!(windows) { "javaw.exe" } else { "java" };
        assert_eq!(Path::new(command.get_program()), root.join("runtime").join("bin").join(expected));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn a_module_entry_uses_the_module_path() {
        let root = root();
        let entry = entry(r#"{"modulePath":["app"],"mainModule":"demo","mainClass":"demo.Main"}"#);

        let command = command(&root, &entry, &[]).unwrap();

        assert_eq!(args(&command), vec!["--module-path".to_string(), root.join("app").display().to_string(), "-m".into(), "demo/demo.Main".into()]);
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn the_machines_java_comes_from_java_home_first_then_the_path() {
        let root = root();
        let name = if cfg!(windows) { "javaw.exe" } else { "java" };
        let home = root.join("runtime");
        let other = root.join("other").join("bin");
        fs::create_dir_all(&other).unwrap();
        fs::write(other.join(name), "").unwrap();
        let path = std::env::join_paths([root.join("empty"), other.clone()]).unwrap();

        assert_eq!(system_java(name, Some(home.clone().into()), Some(path.clone())), Some(home.join("bin").join(name)));
        assert_eq!(system_java(name, Some(root.join("no-java").into()), Some(path.clone())), Some(other.join(name)), "a JAVA_HOME without Java is passed over");
        assert_eq!(system_java(name, None, Some(path)), Some(other.join(name)));
        assert_eq!(system_java(name, None, None), None);
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn an_entry_on_the_machines_java_reports_when_there_is_none() {
        let entry = entry(r#"{"runtime":"system","classpath":["app/demo.jar"],"mainClass":"demo.Main"}"#);
        // The test process's own JAVA_HOME and PATH decide; only the message is checked when nothing is found.
        if let Err(error) = command(Path::new("no-such-install-folder"), &entry, &[]) {
            assert!(error.contains("no Java was found"), "{error}");
        }
    }

    #[test]
    fn a_missing_runtime_is_reported_and_nothing_is_started() {
        let entry = entry(r#"{"classpath":["app/demo.jar"],"mainClass":"demo.Main"}"#);
        let error = command(Path::new("no-such-install-folder"), &entry, &[]).unwrap_err();
        assert!(error.contains("runtime is missing"), "{error}");
    }
}
