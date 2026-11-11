# jpack

**Updates for Java desktop apps: the jars, the bundled Java runtime and any other files.**

jpack puts a small native program, the *stub*, next to your app. The stub starts the app on its own Java runtime, downloads new
releases in the background or on demand, verifies them, swaps the files in while nothing is running, and starts the new version.
A Maven plugin builds everything (runtime, install folder, release files, archives, AppImage); a dependency-free Java library lets
your app or its launcher show the progress however you like.

It works with JavaFX, Swing, [Jolt](https://github.com/B077AS/jolt) or anything else that runs on the JVM, and needs nothing from
the machine it is installed on: no Java, no admin rights, no store. Releases are plain files, so a GitHub release is enough to
host them.

- [Features](#features)
- [How it works](#how-it-works)
- [Quick start](#quick-start)
- [Configuration](#configuration)
- [The Java library](#the-java-library)
- [Launcher patterns](#launcher-patterns)
- [Releases and hosting](#releases-and-hosting)
- [Safety and integrity](#safety-and-integrity)
- [AppImage](#appimage)
- [Windows branding](#windows-branding)
- [Environment variables](#environment-variables)
- [The manifest](#the-manifest)
- [Building from source](#building-from-source)

## Features

**Updating**
- Updates **everything**: your jars, the Java runtime, native libraries, assets, plugins, the launcher, and the stub itself.
- **Component-level downloads**: a release is a set of *parts*, each a zip named by its hash. A part that did not change is not
  downloaded again, so a new jar costs the jar and nothing else.
- **A new Java runtime is no special case**: it is swapped in together with the app, also from a launcher that is itself running
  on the old runtime (a Java 25 launcher can fetch Java 27 and hand over to an app that runs on it).
- **Resumable, verified downloads**: HTTP range requests continue a cut-off download; every part is checked against its SHA-256
  before it is used; failed attempts are retried.
- **Atomic swaps**: new files are downloaded and unpacked next to the old ones, then switched in with folder renames, which take
  milliseconds whatever their size. An interrupted swap (power cut, crash) is finished on the next start; the app never runs with
  half old, half new files.
- **Safe by default**: the previous version is kept until the new one has started, then deleted. A version that cannot start is
  taken out again automatically and is not offered a second time. Only one version is on disk in normal operation.
- **Several windows**: any number of copies of the app can run; files are only swapped when the last one has closed.
- **Self-updating stub**: the stub replaces itself like any other part (the running executable is renamed aside and removed on the
  next start).

**Your choice of experience**
- **`background`**: the stub looks for updates a few seconds after the app starts and then every few hours; what it downloads is
  used the next time the app starts. The app does not have to contain any update code.
- **`manual`**: nothing happens unless the app or its launcher asks.
- **Launcher flow**: a launcher (JavaFX, Swing, Jolt, anything) shows "checking / downloading 42% / installing", exits, and the stub
  swaps the files and starts the updated app, all in the same launch.
- **In-app flow**: the app downloads while the user works and offers "Restart to update".
- You draw all the UI. jpack only reports numbers.

**Packaging (Maven plugin)**
- One goal, `jpack:package`, builds the install folder, the release files and the archives.
- **Builds the Java runtime for you** with `jlink`; the modules are detected with `jdeps` for the app and the launcher together
  (override or extend them with a setting).
- **Settings file**: `src/jpack/jpack.json` holds everything beyond the essentials, so the pom stays short. Pom settings override the
  file; misspelled settings are an error, not silently ignored.
- **Archives**: a `.zip` (Windows) or `.tar.gz` (Linux, executable bits preserved) of the install folder.
- **Launcher as a dependency**: take the launcher from a jar file or from a dependency of the project.
- **Extra parts**: any folder (plugins, assets, models) can be a part that updates on its own.
- **Reproducible output**: the same inputs give the same bytes, so unchanged parts keep their hash from release to release.
- **Build-tool independent core**: the packager is plain Java with no Maven in it.

**Windows**
- **Your name and icon everywhere**: the stub and a renamed copy of `javaw.exe` carry your app's icon and file properties, so the
  task manager, taskbar and Explorer say "MyApp" and not "Java Platform SE binary". Done in pure Java, with no external tool.
- **Per-user installs** need no administrator rights; updates are written next to the app.
- Fatal problems show a message box instead of failing silently.

**Linux**
- **AppImage** builds with `appimagetool`, and an AppImage **updates itself by replacing its own file**, fetching only the blocks
  that changed (see [AppImage](#appimage)).
- `.tar.gz` installs update in place like the Windows ones.
- The stub is one executable with its own TLS (rustls) and no dependencies beyond glibc, built against glibc 2.35 so that it runs on
  that version and newer.

**Integration**
- **Arguments pass through**: whatever the stub is started with (a file, a `myapp://invite/...` link) reaches the program it starts.
- **`JPACK_*` variables** tell the program where it is installed, which version it is, and how to reach the stub.
- **Logging** to `jpack.log` next to the stub (or `~/.local/state/jpack/` for an AppImage), with `JPACK_DEBUG=1` to also print it.

## How it works

```
MyApp/
  MyApp.exe          the stub: the only program that starts processes
  jpack.json         what is installed (the manifest)
  runtime/           part: the Java runtime
  app/               part: your jars
  launcher/          part: your launcher (optional)
  update/            a downloaded release waiting to be swapped in (READY is written last)
  old/               what the last swap replaced; deleted once the new version has started
```

One launch:

1. The stub finishes any swap an earlier run left half done, then swaps in a downloaded release if one is waiting.
2. It starts the start entry from `jpack.json` (the app, or the launcher) on the installed runtime, and waits.
3. While the program runs, the stub serves its requests (`check`, `download`, progress events) over a loopback channel, and with the
   `background` policy checks for updates itself.
4. When the program exits, the stub swaps in what was downloaded, then starts whatever the program asked for next. A launcher asks
   for the app: `jpack.startAfterExit("app")`.
5. A release that was just swapped in is on trial. If its first start fails before saying hello (and before running ten seconds),
   the old files are put back. Otherwise the replaced files are deleted.

Because only the stub ever starts processes and no Java process is running during a swap, nothing is ever replaced under a running
JVM: not the jars, not the runtime, not the launcher.

## Quick start

### 1. Add the plugin

```xml
<plugin>
  <groupId>io.github.b077as</groupId>
  <artifactId>jpack-maven-plugin</artifactId>
  <version>0.1.0</version>
  <executions>
    <execution>
      <goals><goal>package</goal></goals>
    </execution>
  </executions>
  <configuration>
    <mainClass>my.app.Main</mainClass>
    <updateUrl>https://github.com/owner/repo/releases/latest/download</updateUrl>
  </configuration>
</plugin>
```

The plugin carries the stubs for Windows x64 and Linux x64 inside its jar. You do not need Rust. The app's jar must be runnable
with its dependencies (a shaded/fat jar), or set `includeDependencies` to copy the project's runtime dependencies next to it.

### 2. Build

```
mvn package
```

```
target/jpack/
  image/windows-x64/   the install folder: MyApp.exe, jpack.json, runtime/, app/
  dist/                MyApp-1.0.0-windows-x64.zip   (or .tar.gz on Linux)
  release/             jpack-windows-x64.json + one zip per part   <- upload all of these
```

Wrap `image/<platform>/` in an installer (Inno Setup, WiX, a `.deb`...), or hand out the archive from `dist/`.

### 3. Use the library (optional)

```xml
<dependency>
  <groupId>io.github.b077as</groupId>
  <artifactId>jpack-api</artifactId>
  <version>0.1.0</version>
</dependency>
```

```java
if (Jpack.isAvailable()) {                       // false when started from the IDE
    Jpack jpack = Jpack.connect();
    jpack.onProgress((received, total) -> updateBar(received, total));
    jpack.onReady(version -> offerRestart(version));
    jpack.check().thenAccept(update -> {
        if (update.available()) jpack.download();
    });
}
```

### 4. Publish a release

Upload **every file in `target/jpack/release/`** to the address in `updateUrl` (for GitHub: attach them to a release). Installed
copies pick the new version up on their next check.

## Configuration

Settings come from three places, in this order of priority: the **pom** (`<configuration>` or `-Djpack.*` properties), the
**`src/jpack/jpack.json`** file, and **defaults**. A project usually needs only `mainClass` and `updateUrl` in the pom.

### Plugin parameters

| Parameter | Property | Default | Meaning |
|---|---|---|---|
| `name` | `jpack.name` | project name | The app's name as people see it |
| `version` | `jpack.version` | project version | The release's version |
| `executable` | `jpack.executable` | name without spaces | File name of the stub, without `.exe` |
| `platform` | `jpack.platform` | this machine, e.g. `windows-x64` | What to build for |
| `mainClass` | `jpack.mainClass` | – | The class with `main` |
| `mainModule` | `jpack.mainModule` | – | For a modular app: the app's jars go on the module path |
| `jvmArgs` | – | – | JVM options; `${home}` stands for the install folder |
| `args` | – | – | Program arguments, placed before the ones the user started it with |
| `env` | – | – | Environment variables for the program |
| `console` | `jpack.console` | `false` | Windows: start with a console (`java.exe`) |
| `jar` | `jpack.jar` | the project's jar | The app's jar |
| `includeDependencies` | `jpack.includeDependencies` | `false` | Copy runtime dependencies next to the jar and put them on the class path |
| `updateUrl` | `jpack.updateUrl` | – | The folder the release files are served from |
| `policy` | `jpack.policy` | `manual` with a launcher, else `background` | `background` or `manual` |
| `intervalHours` | `jpack.intervalHours` | `4` | `background`: hours between two looks while the app stays open |
| `launcher` | – | – | `{ jar \| dependency, mainClass, jvmArgs }` (see below) |
| `parts` | – | – | Extra folders that update on their own: `{ name, directory }` |
| `runtime` | `jpack.runtime` | built with jlink | A ready runtime folder to ship instead |
| `modules` | `jpack.modules` | detected with jdeps | Modules for the runtime (replaces detection) |
| `addModules` | `jpack.addModules` | – | Modules to add to the detected ones |
| `jmods` | `jpack.jmods` | this JDK | The `jmods` folder of the JDK to build the runtime from |
| `jlinkOptions` | – | – | Further `jlink` options |
| `branding` | – | – | `{ icon, description, company, copyright, product }` (Windows) |
| `brandJava` | `jpack.brandJava` | `true` | Give the Java launcher the app's name and icon too |
| `javaExecutable` | `jpack.javaExecutable` | set by branding | Java executable for every entry, relative to the install folder |
| `archive` | `jpack.archive` | `true` | Also write the `.zip`/`.tar.gz` to `dist/` |
| `appImage` | `jpack.appImage` | `true` if `appimagetool` is found | Also build an AppImage (Linux) |
| `appImageTool` | `jpack.appImageTool` | `$APPIMAGETOOL`, then the `PATH` | The `appimagetool` executable |
| `stub` | `jpack.stub` | the one in the plugin | A stub binary to use instead |
| `includeStub` | `jpack.includeStub` | `true` | Make the stub a part so releases can replace it |
| `configFile` | `jpack.configFile` | `src/jpack/jpack.json` | The settings file |
| `outputDirectory` | `jpack.outputDirectory` | `target/jpack` | Where `image/`, `release/` and `dist/` go |
| `skip` | `jpack.skip` | `false` | Skip the goal |

### `src/jpack/jpack.json`

Every key is optional and is one of the parameters above.

```json
{
  "name": "Komm",
  "mainClass": "komm.Launcher",
  "updateUrl": "https://github.com/owner/repo/releases/latest/download",

  "jvmArgs": [
    "-Xmx2g",
    "--enable-native-access=ALL-UNNAMED",
    "-Dapp.version=${project.version}",
    "-Dapp.home=${home}"
  ],

  "branding": {
    "icon": "src/jpack/icon.ico",
    "description": "Komm",
    "company": "Komm"
  },

  "launcher": {
    "dependency": "my.group:my-launcher",
    "mainClass": "my.launcher.Main",
    "jvmArgs": ["-XX:+UseSerialGC", "-Xmx128m"]
  },

  "parts": [
    { "name": "plugins", "directory": "src/plugins" }
  ]
}
```

- `${project.version}`, `${project.basedir}`, `${project.build.directory}`, the pom's properties and `-D` properties are replaced.
  Anything else, such as the stub's `${home}`, is left as written.
- Relative paths are relative to the project folder.
- **Conventions**: `src/jpack/icon.ico` is the Windows icon; `src/jpack/icon.png` is the AppImage's icon.

### The launcher

A launcher is a program that runs *before* the app: it checks, shows the download, and hands over. Give it as a self-contained (fat)
jar, either by path or as a dependency of the project:

```xml
<dependency>
  <groupId>my.group</groupId>
  <artifactId>my-launcher</artifactId>
  <version>1.0.0</version>
  <scope>provided</scope>   <!-- not on the app's own class path -->
</dependency>
```

```json
{ "launcher": { "dependency": "my.group:my-launcher", "mainClass": "my.launcher.Main" } }
```

With a launcher, the stub starts it first; the app starts when the launcher asks. The launcher and the app share one runtime and one
version; both update with every release.

## The Java library

`jpack-api` has no dependencies and works from Java 17.

```java
Jpack.isAvailable()          // started by a stub? (false from an IDE)
Jpack jpack = Jpack.connect();
```

| Method | Purpose |
|---|---|
| `appName()`, `appVersion()` | What is installed and running |
| `entry()` | The launch entry this program was started as (`app`, `launcher`) |
| `canUpdate()` | `false` when there is no update address or the install location cannot be written to |
| `updatesInBackground()` | `true` with the `background` policy |
| `downloadedVersion()` | The version that is downloaded and waits for the next start, if any |
| `check()` | `CompletableFuture<UpdateCheck>` – `available()`, `version()`, `downloadSize()`, `downloaded()` |
| `download()` | Downloads the newest release; completes with its version, or empty when up to date |
| `download(listener)` | The same, reporting `progress(received, total)` for this download |
| `onProgress(listener)` | Progress of every download, including the stub's own background ones |
| `onReady(listener)` | Called with the version when a release is downloaded and waits, including background ones |
| `startAfterExit(entry)` | What the stub starts once this program has exited (after swapping a downloaded release in) |
| `restartAfterExit()` | `startAfterExit` of this program's own entry |
| `cancelStartAfterExit()` | Takes either back |
| `close()` | Closes the connection (a running download goes on) |

Failures are `JpackException`s with messages written to be shown or logged as they are (`"…answered 404"`, `"…does not match the
hash in the manifest"`, `"the install folder cannot be written to…"`).

Listeners and future callbacks run on the connection's own thread: hop to your UI thread (`Platform.runLater` in JavaFX,
`Application.dispatch` in Jolt, `SwingUtilities.invokeLater` in Swing) before touching a window.

## Launcher patterns

**Silent** – set `policy` to `background` and write no code. The stub downloads and the next start is the new version.

**Launcher window** – the update happens on the same launch:

```java
try (Jpack jpack = Jpack.connect()) {
    UpdateCheck update = jpack.check().get(30, TimeUnit.SECONDS);
    if (update.available()) {
        jpack.download((received, total) ->
                Platform.runLater(() -> bar.setProgress(total == 0 ? 1 : (double) received / total)))
             .get();
    }
    jpack.startAfterExit("app");      // the stub swaps the files in, then starts the app
}
Platform.exit();                        // the launcher must exit for the swap to happen
```

If the update server cannot be reached, skip the download and still call `startAfterExit("app")`: the installed version starts.

**In-app** – download while the user works:

```java
Jpack jpack = Jpack.connect();
jpack.onReady(version -> Platform.runLater(() -> showRestartButton(version)));
jpack.check().thenAccept(u -> { if (u.available()) jpack.download(); });

// when the user clicks "Restart to update":
jpack.restartAfterExit();
System.exit(0);
```

## Releases and hosting

A release is a folder of static files, so any web server, object store or **GitHub release** can host it. The stub fetches
`<updateUrl>/jpack-<platform>.json` and then the zips named in it, using only `GET` and `Range`. No API, no server code, no rate limit.

For GitHub, set `updateUrl` to `https://github.com/<owner>/<repo>/releases/latest/download` and attach every file of
`target/jpack/release/` to each release. Upload all of them every time, including parts that did not change: a user who skips a
version needs whichever parts changed since theirs. Build one release per platform (`jpack-windows-x64.json`,
`jpack-linux-x64.json`); their files do not collide.

A new **Java version** is just a new `runtime` part: build with the newer JDK, publish, and installed apps download that part and
swap it in.

## Safety and integrity

- **https only.** The update address and every file address must be `https://` (plain `http://` is accepted only for `localhost`,
  for local testing).
- **Every part is verified** against the SHA-256 in the manifest before it is unpacked; a mismatch discards the download.
- **Paths are validated.** Part names and every path in the manifest must stay inside the install folder; a zip entry that would
  land outside its part makes the whole part unusable. Part names that clash with the stub's own folders are refused.
- **Bounded input.** The manifest is limited to 1 MB; the stub's channel limits every line; a download larger than the manifest says
  is refused.
- **The channel is local and authenticated.** The stub listens on `127.0.0.1` on a random port; the program receives a one-time token
  in its environment and must present it first. Nothing else can ask the stub to download or restart.
- **Failure-proof swaps.** Nothing installed is touched until a release is complete and verified. A swap that cannot finish (a file
  held by an antivirus scanner, say) is undone completely and retried later; one that is interrupted is finished on the next start.
- **Trial and rollback.** A release that crashes on its first start is rolled back and not offered again.
- **Releases are compared by content** (version, parts and launch settings). The stub moves to whatever release is published when
  it differs from what is installed, so publishing an earlier release again rolls everyone back to it.

## AppImage

On Linux with [`appimagetool`](https://github.com/AppImage/appimagetool) available (`$APPIMAGETOOL` or on the `PATH`), the plugin
also builds an AppImage of the install folder. The stub is its `AppRun`.

An AppImage is one read-only file, so it updates by **replacing itself**:

1. The release publishes the AppImage and a small **block index** (about 0.1% of its size): a checksum per 16 KiB block.
2. The stub scans the installed AppImage for blocks the new one contains, at any position (a rolling checksum finds blocks that
   moved), and builds the new file from those plus the blocks it fetches with HTTP range requests.
3. The result is checked against the hash in the manifest and renamed over the old file. The running program keeps working from the
   old file until it exits; then the new AppImage starts the app.

Only the changed blocks cross the network, even when a changed jar shifts everything after it. Without a block index, the whole
file is fetched (resumably).

Files in the release: `MyApp-1.0.0-x86_64.AppImage`, `MyApp-1.0.0-x86_64.AppImage.blocks`, and the manifest, which names both.

`appimagetool` is needed only on the machine that builds, never on your users' machines: they get the finished `.AppImage`. It is not
bundled with the plugin, and it only runs on Linux (an AppImage needs Unix permissions and symbolic links). Without it the plugin
skips the AppImage and builds everything else. It is itself an AppImage and the plugin runs it without FUSE, so downloading the file
and making it executable is all the setup. In a GitHub Actions job on `ubuntu-latest`:

```yaml
- name: Get appimagetool
  run: |
    curl -fsSL -o appimagetool https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage
    echo "ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0  appimagetool" | sha256sum -c -
    chmod +x appimagetool
    echo "APPIMAGETOOL=$PWD/appimagetool" >> "$GITHUB_ENV"

- name: Build the release
  run: mvn -B package
```

The `sha256sum` line pins that exact build. If `appimagetool` is not found the build still succeeds, only without the AppImage (a
warning says so when `appImage` is `true`), so check that the `.AppImage` is among the files you publish.

## Windows branding

By default the plugin edits the Windows executables it ships so that nothing says "Java":

- the **stub** (`MyApp.exe`) gets your icon and file properties,
- the runtime gets a copy of `javaw.exe` named `MyApp.exe` with the same icon and properties, and every entry uses it, so the
  *running program* appears under your name in the task manager and taskbar.

```json
{ "branding": { "icon": "src/jpack/icon.ico", "description": "My App", "company": "Acme", "copyright": "© Acme", "product": "My App" } }
```

The edit is made in pure Java: the resource section is rebuilt and appended, so it works whatever a program's section layout, and
a signature that the edit would invalidate is removed. The stub is branded with its own version and the Java launcher with the
runtime's version, never the app's, so these files stay identical between releases and are not downloaded again.

## Environment variables

Set by the stub for the program it starts:

| Variable | Value |
|---|---|
| `JPACK_HOME` | The install folder |
| `JPACK_EXE` | The file to run to start the app again: the stub, or the AppImage file (use it for `myapp://` registrations and shortcuts) |
| `JPACK_NAME`, `JPACK_VERSION` | The installed app's name and version |
| `JPACK_ENTRY` | The launch entry that was started |
| `JPACK_PORT`, `JPACK_TOKEN` | The channel to the stub (used by `Jpack.connect()`) |

Read by the stub:

| Variable | Effect |
|---|---|
| `JPACK_DEBUG` | Also print the log to the console |
| `JPACK_NO_DIALOG` | Windows: show no message box on a fatal error |
| `JPACK_CHECK_DELAY_MS`, `JPACK_CHECK_INTERVAL_MS` | Timing of the `background` checks (default 5 s, then every `intervalHours`) |
| `JPACK_START` | Start this entry instead of the manifest's start entry |

## The manifest

`jpack-<platform>.json` is generated by the plugin; you never write it. The same format is the installed `jpack.json`.

```json
{
  "format": 1,
  "name": "Komm",
  "version": "1.2.0",
  "update": { "url": "https://github.com/owner/repo/releases/latest/download/jpack-windows-x64.json", "policy": "manual" },
  "parts": {
    "runtime":  { "file": "runtime-2c3e5ec10c6104ac.zip",  "sha256": "2c3e…", "size": 46429515 },
    "app":      { "file": "app-380fb67db5a2a38f.zip",      "sha256": "380f…", "size": 80591490 },
    "launcher": { "file": "launcher-58706f75cec5a0ef.zip", "sha256": "5870…", "size": 18408313 },
    "stub":     { "file": "stub-471a0416e6ab4b7c.zip",     "sha256": "471a…", "size": 504884 }
  },
  "launch": {
    "start": "launcher",
    "entries": {
      "launcher": { "runtime": "runtime", "java": "runtime/bin/Komm.exe", "jvmArgs": ["-Xmx128m"],
                    "classpath": ["launcher/launcher.jar"], "mainClass": "my.launcher.Main" },
      "app":      { "runtime": "runtime", "java": "runtime/bin/Komm.exe", "jvmArgs": ["-Xmx2g"],
                    "classpath": ["app/komm-1.2.0.jar"], "mainClass": "komm.Launcher" }
    }
  }
}
```

An entry can also set `modulePath`, `mainModule`, `args`, `env` and `console`; `classpath` entries may end in `/*` to mean every jar in a
folder. The published manifest of an AppImage release additionally has an `appImage` section (file, hash, size and block index).

## Building from source

Requirements: JDK 17+ and Maven 3.9+ for the Java side (the tests build runtimes with `jlink`), and [Rust](https://rustup.rs) to
build the stub.

```
./build-stub.ps1         # Windows;  or  sh build-stub.sh  on Linux. Puts the stub in stubs/<platform>/
mvn install              # library, plugin, end-to-end tests (they run the real stub)
cd stub && cargo test    # the stub's own tests
```

The plugin bundles whatever is in `stubs/<platform>/` at build time. Build the Linux stub on Linux with `sh build-stub.sh`, on Ubuntu
22.04 or another system with an equally old glibc: the stub runs on the glibc version it was built against and on newer ones. With
`appimagetool` and the `file` command installed, the end-to-end tests also build and update a real AppImage.

| Module | What it is |
|---|---|
| `stub/` | The Rust stub |
| `jpack-api/` | The Java library (`io.github.b077as.jpack`) |
| `jpack-maven-plugin/` | The packager (`…jpack.build`, usable without Maven) and the `jpack:package` goal (`…jpack.maven`) |
| `jpack-tests/` | End-to-end tests: real releases, a local web server, the real stub, real runtimes |

## License

[Apache License 2.0](LICENSE). The stub contains third-party Rust crates, all under permissive licenses; see
[THIRD-PARTY.md](THIRD-PARTY.md).
