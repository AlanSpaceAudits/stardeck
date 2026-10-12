#!/usr/bin/env bash
# Build the Linux and Windows PC app and the Android debug APK into dist/.
# The Windows target uses the isolated rustup toolchain (x86_64-pc-windows-gnu) and mingw-w64-gcc.
set -euo pipefail
cd "$(dirname "$0")/.."
export RUSTUP_HOME="${RUSTUP_HOME:-$HOME/.local/share/rustup}"
export CARGO_HOME="${CARGO_HOME:-$HOME/.local/share/rustup-cargo}"
export PATH="$CARGO_HOME/bin:$PATH"
export CARGO_TARGET_DIR="$PWD/seestar_pc/target/cross"

mkdir -p dist
(cd seestar_pc && cargo build --release && cargo build --release --target x86_64-pc-windows-gnu)
cp seestar_pc/target/cross/release/seestar_pc dist/stardeck-linux-x86_64
cp seestar_pc/target/cross/x86_64-pc-windows-gnu/release/seestar_pc.exe dist/stardeck-windows-x86_64.exe

cp seestar_pc/src/catalog.tsv seestar_key.pem android/app/src/main/assets/
(cd android && ./gradlew --no-daemon assembleDebug > build.log 2>&1 < /dev/null)
cp android/app/build/outputs/apk/debug/app-debug.apk dist/stardeck-android-debug.apk
ls -la dist
