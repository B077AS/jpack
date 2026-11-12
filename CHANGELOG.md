# Changelog

All published versions of jpack, newest first. jpack is early software (version 0.x): APIs and file formats can still change
between versions, and this file says when they do.

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
