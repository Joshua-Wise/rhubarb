#!/bin/sh
# Builds librexterm.so (Ghostty VT + JNI bridge) into app/src/main/jniLibs/.
# Needs Zig 0.17 and the Android NDK. Caches live outside iCloud-synced ~/Documents.
set -eu
cd "$(dirname "$0")/../native"
: "${ZIG:=zig}"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the Android NDK root}"
CACHE="${REXTERM_CACHE:-$HOME/Library/Caches/rexAndroid/zig}"
export ZIG_GLOBAL_CACHE_DIR="$CACHE/global" ZIG_LOCAL_CACHE_DIR="$CACHE/local"
exec "$ZIG" build jni -Doptimize=ReleaseFast --prefix "$CACHE/out" "$@"
