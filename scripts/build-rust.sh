#!/usr/bin/env bash
# Build the Rust workspace for Android via cargo-ndk.
#
#   boxagent-core cdylib -> $JNILIBS/<abi>/libboxagent.so      (JNI into app)
#   boxagentd    bin     -> $JNILIBS/<abi>/libboxagentd.so     (pushed to device)
#
# Usage: build-rust.sh <absolute jniLibs dir>
set -euo pipefail

JNILIBS="$1"
cd "$(dirname "$0")/../core"

# Stamp the daemon with the app version (see boxagentd/build.rs). Gradle
# passes the same value via the cargoNdkBuild task env; standalone builds
# fall back to the latest git tag so both paths stay consistent.
if [ -z "${BOXAGENT_APP_VERSION:-}" ]; then
    BOXAGENT_APP_VERSION="$(git describe --tags --abbrev=0 2>/dev/null | sed 's/^v//' || true)"
    # Same fallback as app/build.gradle.kts, or the app sees a version
    # mismatch on every reconnect and respawns the daemon.
    BOXAGENT_APP_VERSION="${BOXAGENT_APP_VERSION:-0.1.0-dev}"
fi
export BOXAGENT_APP_VERSION

ABIS=("arm64-v8a" "x86_64")

cargo ndk -t arm64-v8a -t x86_64 -o "$JNILIBS" build --release -p boxagent-core
cargo ndk -t arm64-v8a -t x86_64 build --release -p boxagentd

declare -A TRIPLE=( [arm64-v8a]=aarch64-linux-android [x86_64]=x86_64-linux-android )
for abi in "${ABIS[@]}"; do
    t="${TRIPLE[$abi]}"
    mkdir -p "$JNILIBS/$abi"
    cp "target/$t/release/boxagentd" "$JNILIBS/$abi/libboxagentd.so"
    # The app reads these bytes out of the APK and pushes them over ADB
    # (native libs are stored uncompressed and never extracted); the spawn
    # command chmods the pushed copy.
    chmod 755 "$JNILIBS/$abi/libboxagentd.so"
done
echo "rust build done -> $JNILIBS"
