# Changelog

All published versions of jpack, newest first. jpack is early software (version 0.x): APIs and file formats can still change
between versions, and this file says when they do.

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
