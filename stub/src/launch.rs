//! Turning a launch entry of the manifest into a Java command line.

use crate::layout::resolve;
use crate::manifest::Entry;
use std::ffi::OsString;
use std::path::{Path, PathBuf};
use std::process::Command;

/// The command that starts `entry`. `extra` are the arguments the stub itself was started with (a file to open, an
/// invite link, ...): they go to the program after the entry's own arguments.
pub fn command(root: &Path, entry: &Entry, extra: &[OsString]) -> Result<Command, String> {
    let java = java_executable(root, entry);
    if !java.is_file() {
        return Err(format!("the Java runtime is missing: {} does not exist", java.display()));
    }
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
    let name = if cfg!(windows) {
        if entry.console {
            "java.exe"
        } else {
            "javaw.exe"
        }
    } else {
        "java"
    };
    resolve(root, &entry.runtime).join("bin").join(name)
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
    fn a_missing_runtime_is_reported_and_nothing_is_started() {
        let entry = entry(r#"{"classpath":["app/demo.jar"],"mainClass":"demo.Main"}"#);
        let error = command(Path::new("no-such-install-folder"), &entry, &[]).unwrap_err();
        assert!(error.contains("runtime is missing"), "{error}");
    }
}
