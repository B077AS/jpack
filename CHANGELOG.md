# Changelog

All published versions of jpack, newest first. jpack is early software (version 0.x): APIs and file formats can still change
between versions, and this file says when they do.

## 0.1.3 - 2026-11-16

### Added
- **`systemJava`**: a release without a Java runtime. The entries run on the Java installed on the machine (`JAVA_HOME`, else
  `java` on the `PATH`); the stub says `no Java was found` and exits with 126 when there is none. The manifest marks those entries
  `"runtime": "system"`, which an older stub does not understand, so the installed stub has to come from this version. `system` can
  no longer be the name of a part.
- **Command line launchers on Windows**: `"console": true` in `launcher` starts it with `java.exe`, and a console entry started from a
  terminal now runs in that terminal instead of opening a new window whose output is lost: when the stub was given no output
  streams, it attaches to the terminal it was started from and hands it to the entry. Streams the stub was given (a pipe, a
  file, a service wrapper's log) are passed on untouched.
- **`start`**: which entry the stub starts unless `JPACK_START` says otherwise. `"start": "app"` with a launcher suits a server
  whose launcher is a command line tool started with `JPACK_START=launcher`.
- **`log`**: `"log": false` in `jpack.json` (or `log` in the pom, `-Djpack.log=false`) stops the stub from writing `jpack.log`.
  A failure that stops the app is still logged. The setting is part of the manifest (`"log": false`), so it applies to
  installations once they have the release; an older stub ignores it and keeps logging. It does not make a new release by itself.
- **Hand-over without a blank moment**: `Jpack.startNow(entry)` starts the app while the launcher is still open, the app calls
  `notifyShown()` once its window is visible, and the launcher waits for that with `whenShown(entry)` and closes. Before, nothing was on
  screen between the launcher closing and the app's window appearing. When a downloaded update waits to be swapped in, `startNow`
  does what `startAfterExit` does and completes empty (files are only swapped while no Java program runs). If the app exits before it
  says so, `whenShown` fails with its exit code, so the launcher can show an error instead of vanishing. The stub's exit code is the
  app's. With an older stub, `startNow` falls back to `startAfterExit` and `notifyShown` does nothing (tried against the 0.1.2 stub).
- **The update check starts before the launcher asks.** With the `manual` policy and a launcher, the stub looks at the update address
  as soon as it starts. The launcher's `check()` then usually answers at once, and that one look also serves a `download()` that
  follows within a minute (one manifest request per launch instead of two).

### Changed
- **Only the start entry's run decides a trial.** A run started with `JPACK_START` naming another entry no longer confirms (or rolls
  back) a release that was just swapped in; it stays on trial until the start entry has started. A tool run next to a server can no
  longer confirm an update for a server that then fails.
- **The stub leaves no lock files behind.** `jpack.lock` and `jpack.update.lock` are gone from the install folder. On Linux the locks
  are taken on the install folder and on `jpack.json`, so nothing is created; on Windows a small file in the temp folder exists
  only while the app runs.
- **Each program the stub starts gets its own `JPACK_TOKEN`**, so the stub knows whether the launcher or the app is talking, and a
  token stops working once its program has exited. The stub's `hello` answer lists what it understands (`features`).

## 0.1.2 - 2026-11-12

### Fixed
- **`rawParts` set in `jpack.json` was ignored** (0.1.1). Maven hands a list parameter that has a `property` binding to the plugin as an
  empty list, not null, and the empty list won over the value from `jpack.json`. `rawParts` was the only list or map setting
  affected, and it worked when set in the pom or with `-Djpack.rawParts`. An empty list or map now counts as "not set".

### Changed
- The stub reports its own version, now 0.1.2.

## 0.1.1 - 2026-11-11

### Added
- **`rawParts`**: parts that consist of one file, such as `["app", "launcher"]` for two jars, are published as that file and not
  as a zip: nothing to unpack, and a jar built once is the same file for every platform. The stub copies the file into the
  part's folder. The manifest then has `"format": 2` and `"packed": false` on those parts. **A stub older than 0.1.1 refuses
  such a release**, so use it only once your installations have a 0.1.1 stub (a release that keeps zips is still format 1).
- **`appImageName`**: the file name of the AppImage, for example `MyApp.AppImage`, so that
  `releases/latest/download/MyApp.AppImage` is a link that never changes and no copy has to be uploaded. The block index is
  named after it (`MyApp.AppImage.blocks`).

### Changed
- **Part files are named after the part and the platform, without a hash**: `runtime-linux-x64.zip` instead of
  `runtime-5f41f7bb843f5df7.zip`. The manifest lists the SHA-256 of every part and the stub checks it, so the name does not need
  to. Upload the parts first and the manifest last. Installations of 0.1.0 update to such a release as before: the stub compares
  parts by hash, not by file name.

### Fixed
- **The stub dropped the connection of a program that stayed silent for 10 seconds** (Windows). The handshake timeout was lifted
  on the original socket but stayed on the clone the stub reads from, so a launcher waiting for a long download could not send its
  next request (`startAfterExit` ended with "The jpack stub did not answer"), although the download itself had finished.

## 0.1.0 - 2026-11-11

### Added
- **The stub**: a small native program (Rust, 1 MB on Windows, 2 MB on Linux) installed next to the app. It starts the app on
  its own Java runtime, downloads updates, verifies them, swaps the files in while no program is running, and falls back to the
  previous version when a new one cannot start.
- **Component updates**: a release is a set of parts (Java runtime, app, launcher, stub, your own folders), each a zip named by
  its hash; only the parts that changed are downloaded, with resumable range requests and hash checks.
- **Runtime updates**: a new Java runtime is swapped in together with the app, also from a launcher that runs on the old one.
- **Update policies**: `background` (the stub looks for updates while the app runs) and `manual` (the app or its launcher asks).
- **`jpack-api`**: a dependency-free Java library to check, download with progress, and restart into an update.
- **`jpack-maven-plugin`**: the `package` goal builds the install folder, the release files, a jlink runtime, `.zip`/`.tar.gz`
  archives and, with `appimagetool`, an AppImage. Settings live in the pom and/or `src/jpack/jpack.json`.
- **Windows branding**: the stub and a renamed copy of `javaw.exe` carry the app's icon and name, edited in pure Java.
- **AppImage updates**: an AppImage replaces its own file, fetching only the blocks that changed (block index with a rolling
  checksum, HTTP range requests).
- Windows x64 and Linux x64 stubs, bundled in the plugin.
