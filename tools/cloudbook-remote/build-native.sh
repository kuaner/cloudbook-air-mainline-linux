#!/usr/bin/env bash
set -euo pipefail
project=$(cd -- "$(dirname -- "$0")" && pwd)
ndk=$1
cargo_output=$2
jni_output=$3

case $(uname -s) in
    Darwin) toolchain=darwin-x86_64 ;;
    Linux) toolchain=linux-x86_64 ;;
    *) echo 'Build the Android transport from macOS or Linux.' >&2; exit 1 ;;
esac
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ndk/toolchains/llvm/prebuilt/$toolchain/bin/aarch64-linux-android30-clang"
# Enable runtime AES instruction detection on ARMv8, including RK3568.
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="--cfg aes_armv8 ${CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS:-}"
cargo build --manifest-path "$project/native/Cargo.toml" --locked --release \
    --target aarch64-linux-android --target-dir "$cargo_output"
mkdir -p "$jni_output/arm64-v8a"
cp "$cargo_output/aarch64-linux-android/release/libcloudbook_remote.so" "$jni_output/arm64-v8a/"
