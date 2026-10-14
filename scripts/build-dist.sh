#!/usr/bin/env bash
# Build the portable PC bundles (Linux, Windows) and the Android debug APK into dist/.
# Linux: built in a Debian 11 container so the binary runs with glibc 2.31 and later.
# Windows: isolated rustup toolchain (x86_64-pc-windows-gnu) and mingw-w64-gcc.
set -euo pipefail
cd "$(dirname "$0")/.."
FFMPEG_BASE=https://github.com/BtbN/FFmpeg-Builds/releases/download/latest
FFMPEG_LINUX=ffmpeg-n9.0-latest-linux64-lgpl-9.0
FFMPEG_WIN=ffmpeg-n9.0-latest-win64-lgpl-9.0

mkdir -p dist vendor_ffmpeg
[ -f vendor_ffmpeg/$FFMPEG_LINUX.tar.xz ] || curl -fsSL -o vendor_ffmpeg/$FFMPEG_LINUX.tar.xz $FFMPEG_BASE/$FFMPEG_LINUX.tar.xz
[ -f vendor_ffmpeg/$FFMPEG_WIN.zip ] || curl -fsSL -o vendor_ffmpeg/$FFMPEG_WIN.zip $FFMPEG_BASE/$FFMPEG_WIN.zip
[ -f vendor_ffmpeg/ffmpeg ] || tar -xJf vendor_ffmpeg/$FFMPEG_LINUX.tar.xz -C vendor_ffmpeg --strip-components=2 $FFMPEG_LINUX/bin/ffmpeg
[ -f vendor_ffmpeg/ffmpeg.exe ] || unzip -j -o -q vendor_ffmpeg/$FFMPEG_WIN.zip "$FFMPEG_WIN/bin/ffmpeg.exe" -d vendor_ffmpeg

docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -e CARGO_HOME=/src/seestar_pc/target/portable-cargo \
    -e CARGO_TARGET_DIR=/src/seestar_pc/target/portable -v "$PWD":/src -w /src/seestar_pc rust:bullseye cargo build --release

(
    export RUSTUP_HOME="${RUSTUP_HOME:-$HOME/.local/share/rustup}" CARGO_HOME="${CARGO_HOME:-$HOME/.local/share/rustup-cargo}"
    export PATH="$CARGO_HOME/bin:$PATH" CARGO_TARGET_DIR="$PWD/seestar_pc/target/cross"
    cd seestar_pc && cargo build --release --target x86_64-pc-windows-gnu
)

stage=dist/stage
mkdir -p $stage/stardeck-linux-x86_64 $stage/stardeck-windows-x86_64
cp seestar_pc/target/portable/release/seestar_pc $stage/stardeck-linux-x86_64/stardeck
cp seestar_pc/target/portable/release/stardeck_bridge $stage/stardeck-linux-x86_64/stardeck-bridge
cp vendor_ffmpeg/ffmpeg scripts/install-linux.sh $stage/stardeck-linux-x86_64/
cp scripts/README-pc.txt $stage/stardeck-linux-x86_64/README.txt
cp seestar_pc/target/cross/x86_64-pc-windows-gnu/release/seestar_pc.exe $stage/stardeck-windows-x86_64/stardeck.exe
cp seestar_pc/target/cross/x86_64-pc-windows-gnu/release/stardeck_bridge.exe $stage/stardeck-windows-x86_64/stardeck-bridge.exe
cp vendor_ffmpeg/ffmpeg.exe $stage/stardeck-windows-x86_64/
cp scripts/README-pc.txt $stage/stardeck-windows-x86_64/README.txt
tar -C $stage -czf dist/stardeck-linux-x86_64.tar.gz stardeck-linux-x86_64
(cd $stage && zip -q -r ../stardeck-windows-x86_64.zip stardeck-windows-x86_64)

cp seestar_pc/src/catalog.tsv seestar_key.pem android/app/src/main/assets/
(cd android && ./gradlew --no-daemon assembleDebug > build.log 2>&1 < /dev/null)
cp android/app/build/outputs/apk/debug/app-debug.apk dist/stardeck-android-debug.apk
ls -la dist
