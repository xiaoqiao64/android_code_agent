#!/usr/bin/env bash
# Build libtermux.so into app/src/main/jniLibs/arm64-v8a/
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${NDK_DIR:-$ROOT/.android-ndk/android-ndk-r27c}"
CMAKE="${CMAKE_BIN:-$ROOT/.android-cmake/bin/cmake}"
BUILD="$ROOT/.native-build"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT" "$BUILD"
"$CMAKE" -S "$ROOT/app/src/main/cpp" -B "$BUILD" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$OUT"
"$CMAKE" --build "$BUILD" -j4
echo "Built $OUT/libtermux.so"
