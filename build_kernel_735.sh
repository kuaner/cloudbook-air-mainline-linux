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
DTS_SRC=${DTS_SRC:-"$ROOT_DIR/mainline-6.12/rk3568-ecloudbook-air-24rtw-v30-charging-led.dts"}
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
    GIT_CEILING_DIRECTORIES="$ROOT_DIR" git -C "$SRC_DIR" apply --whitespace=nowarn "$PORT_PATCH" 2>/dev/null \
        || patch -d "$SRC_DIR" -p1 --forward < "$PORT_PATCH"
fi

# The 7.3 port patch carries W103D up to 0012; apply the upstream 0013
# throughput/scan fix on top (it applies cleanly to the 7.3 W103D driver).
PATCH_0013="$ROOT_DIR/mainline-6.12/patches/0013-mt76-mt7663s-w103d-tx-ampdu-and-sdio-throughput.patch"
if ! grep -q 'mt7663s_w103d_sync_vendor_connect' \
        "$SRC_DIR/drivers/net/wireless/mediatek/mt76/mt7663s/mt7663s_w103d.c"; then
    echo "Applying W103D 0013 throughput patch ..."
    GIT_CEILING_DIRECTORIES="$ROOT_DIR" git -C "$SRC_DIR" apply --whitespace=nowarn "$PATCH_0013" 2>/dev/null \
        || patch -d "$SRC_DIR" -p1 --forward < "$PATCH_0013"
fi

for board_patch in \
        "$ROOT_DIR/mainline-7.3/0001-drm-analogix-dp-pass-lane-count-to-phy.patch" \
        "$ROOT_DIR/mainline-7.3/0002-drm-rockchip-skip-dirtyfb-on-cloudbook-air.patch" \
        "$ROOT_DIR/mainline-7.3/0003-bluetooth-handle-unsupported-local-pairing-options.patch" \
        "$ROOT_DIR/mainline-7.3/0004-power-sc2021-charger-wakeup.patch" \
        "$ROOT_DIR/mainline-6.12/patches/0014-power-supply-sbs-align-charging-led-status.patch"; do
    if ! patch -d "$SRC_DIR" -p1 --dry-run --reverse --force < "$board_patch" >/dev/null; then
        patch -d "$SRC_DIR" -p1 --forward < "$board_patch"
    fi
done

mkdir -p "$OUT_DIR"
if [[ ! -f "$OUT_DIR/.config" ]]; then
    cp "$ROOT_DIR/mainline-7.3/config-7.3.5-cloudbook" "$OUT_DIR/.config"
fi

# Sync the layered board DTS chain, as build_kernel_612.sh does.
cp "$ROOT_DIR/mainline-6.12/"rk3568-ecloudbook-air-24rtw*.dts \
   "$SRC_DIR/arch/arm64/boot/dts/rockchip/"
cp "$ROOT_DIR/mainline-7.3/$DTS_NAME-v30-usb.dts" "$SRC_DIR/arch/arm64/boot/dts/rockchip/"
cp "$DTS_SRC" "$SRC_DIR/arch/arm64/boot/dts/rockchip/$DTS_NAME.dts"
# Register the two board DTBs explicitly: a direct "make rockchip/foo.dtb"
# target only exists when the DTB is listed in this Makefile.
for d in "$DTS_NAME" "$DTS_NAME-v30-usb"; do
    MK="$SRC_DIR/arch/arm64/boot/dts/rockchip/Makefile"
    grep -qF "+= $d.dtb" "$MK" || \
        sed -i "1a dtb-\$(CONFIG_ARCH_ROCKCHIP) += $d.dtb" "$MK"
done


make -C "$SRC_DIR" O="$OUT_DIR" ARCH=arm64 LLVM=1 olddefconfig
make -C "$SRC_DIR" O="$OUT_DIR" ARCH=arm64 LLVM=1 -j"$JOBS" \
    DTC_FLAGS="-@" Image dtbs
if [[ "$BUILD_MODULES" == "1" ]]; then
    make -C "$SRC_DIR" O="$OUT_DIR" ARCH=arm64 LLVM=1 -j"$JOBS" modules
fi

cp "$OUT_DIR/.config" "$ROOT_DIR/mainline-7.3/config-7.3.5-cloudbook"
sha256sum \
    "$OUT_DIR/arch/arm64/boot/Image" \
    "$OUT_DIR/arch/arm64/boot/dts/rockchip/$DTS_NAME.dtb" \
    "$ROOT_DIR/mainline-7.3/config-7.3.5-cloudbook"
