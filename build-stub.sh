#!/bin/sh
# Builds the stub for this machine and puts it where the Maven plugin and the tests look for it (stubs/<platform>).
# Needs Rust (https://rustup.rs). Run it before `mvn install` whenever the stub's code changed.
set -eu
root="$(cd "$(dirname "$0")" && pwd)"
[ -d "$HOME/.cargo/bin" ] && PATH="$HOME/.cargo/bin:$PATH"

case "$(uname -s)" in
  Linux) os=linux; exe=jpack-stub ;;
  Darwin) os=macos; exe=jpack-stub ;;
  *) os=windows; exe=jpack-stub.exe ;;
esac
case "$(uname -m)" in
  x86_64|amd64) arch=x64 ;;
  aarch64|arm64) arch=arm64 ;;
  *) arch="$(uname -m)" ;;
esac

(cd "$root/stub" && cargo build --release)
mkdir -p "$root/stubs/$os-$arch"
cp "$root/stub/target/release/$exe" "$root/stubs/$os-$arch/$exe"
ls -l "$root/stubs/$os-$arch/$exe"
