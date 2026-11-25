#!/usr/bin/env bash
# Rebuild both kernels, including modules, and the 6.12 boot images.
#
#   ./rebuild_linux.sh
#
# Requires the two source trees:
#   ./linux-6.12       (Linux 6.12.117)
#   ./linux-7.3.5      (Linux 7.3.5)
# and the LLVM toolchain expected by both build scripts.
set -euo pipefail

ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

BUILD_MODULES=1 "$ROOT_DIR/build_kernel_612.sh"
BUILD_MODULES=1 "$ROOT_DIR/build_kernel_735.sh"

"$ROOT_DIR/scripts/pack_linux_artifacts.sh" 6.12
"$ROOT_DIR/scripts/pack_linux_artifacts.sh" 7.3
