#!/usr/bin/env bash
# Build the CloudBook Air 7.3.5 kernel (VDPU346 HEVC + W103D Wi-Fi/BT port).
#
# Prerequisites: Linux 7.3.5 source unpacked at ./linux-7.3.5, clang/lld/llvm,
# make, bc, bison, flex, libssl-dev, libelf-dev, dtc, python3.
set -euo pipefail

ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
SRC_DIR="$ROOT_DIR/linux-7.3.5"
OUT_DIR="$ROOT_DIR/kernel_build/out-7.3.5"
DTS_NAME=rk3568-ecloudbook-air-24rtw
DTS_SRC=${DTS_SRC:-"$ROOT_DIR/mainline-6.12/rk3568-ecloudbook-air-24rtw-v29-emmc-rootfs.dts"}
JOBS=${JOBS:-$(nproc)}
BUILD_MODULES=${BUILD_MODULES:-1}
PORT_PATCH="$ROOT_DIR/mainline-7.3/cloudbook-7.3.5-port.patch"

for tool in clang ld.lld llvm-ar llvm-nm llvm-objcopy make dtc; do
    command -v "$tool" >/dev/null || { echo "Missing required tool: $tool" >&2; exit 1; }
done
[[ -d "$SRC_DIR" ]] || {
    echo "Missing $SRC_DIR. Download Linux 7.3.5 first:" >&2
    echo "  curl -fLO https://cdn.kernel.org/pub/linux/kernel/v7.x/linux-7.3.5.tar.xz" >&2
    echo "  mkdir Linux-7.3.5 && tar -xf linux-7.3.5.tar.xz -C linux-7.3.5 --strip-components=1" >&2
    exit 1
}

# The port patch carries the VDPU346 series plus every board patch. It applies
# cleanly to a pristine 7.3.5 tree; this marker detects an already patched tree.
if ! grep -q 'rockchip,rk3568-vdec' "$SRC_DIR/drivers/media/platform/rockchip/rkvdec/rkvdec.c"; then
    echo "Applying CloudBook 7.3.5 port patch ..."
    git -C "$SRC_DIR" apply --whitespace=nowarn "$PORT_PATCH" 2>/dev/null \
        || patch -d "$SRC_DIR" -p1 --forward < "$PORT_PATCH"
fi

mkdir -p "$OUT_DIR"
if [[ ! -f "$OUT_DIR/.config" ]]; then
    cp "$ROOT_DIR/mainline-7.3/config-7.3.5-cloudbook" "$OUT_DIR/.config"
fi

# Sync the layered board DTS chain, as build_kernel_612.sh does.
cp "$ROOT_DIR/mainline-6.12/"rk3568-ecloudbook-air-24rtw*.dts \
   "$SRC_DIR/arch/arm64/boot/dts/rockchip/"
cp "$DTS_SRC" "$SRC_DIR/arch/arm64/boot/dts/rockchip/$DTS_NAME.dts"

make -C "$SRC_DIR" O="$OUT_DIR" ARCH=arm64 LLVM=1 olddefconfig
make -C "$SRC_DIR" O="$OUT_DIR" ARCH=arm64 LLVM=1 -j"$JOBS" \
    DTC_FLAGS="-@" Image "rockchip/$DTS_NAME.dtb"
if [[ "$BUILD_MODULES" == "1" ]]; then
    make -C "$SRC_DIR" O="$OUT_DIR" ARCH=arm64 LLVM=1 -j"$JOBS" modules
fi

cp "$OUT_DIR/.config" "$ROOT_DIR/mainline-7.3/config-7.3.5-cloudbook"
sha256sum \
    "$OUT_DIR/arch/arm64/boot/Image" \
    "$OUT_DIR/arch/arm64/boot/dts/rockchip/$DTS_NAME.dtb" \
    "$ROOT_DIR/mainline-7.3/config-7.3.5-cloudbook"