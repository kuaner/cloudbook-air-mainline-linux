#!/bin/sh
# Reproducible Rockchip 6.1 Android 14 USB test kernel for CloudBook Air.
# Run in the rkbuild container: /work/scripts/build_kernel_android14.sh
set -eu
SOURCE=${SOURCE:-/src/kernel}
cd "$SOURCE"
OUTPUT=${OUTPUT:-/build-android14-usb}
CONFIG=${CONFIG:-/work/artifacts/android14/config-6.1.172-android14}
PATCH=${PATCH:-/work/artifacts/android14/android14-6.1-board-drivers.patch}
if [ ! -f "$SOURCE/arch/arm64/boot/dts/rockchip/rk3568-ecloudbook-air-24rtw.dts" ]; then
    git apply --check "$PATCH"
    git apply "$PATCH"
fi
python3 /work/scripts/extract_android14_board_dts.py "$PATCH" \
    "$SOURCE/arch/arm64/boot/dts/rockchip/rk3568-ecloudbook-air-24rtw.dts"
mkdir -p "$OUTPUT"
cp "$CONFIG" "$OUTPUT/.config"
make -C "$SOURCE" O="$OUTPUT" ARCH=arm64 LLVM=1 olddefconfig
"$SOURCE/scripts/config" --file "$OUTPUT/.config" -e USB_STORAGE -d USB_MASS_STORAGE
"$SOURCE/scripts/config" --file "$OUTPUT/.config" -e DRM_PANEL_EDP
"$SOURCE/scripts/config" --file "$OUTPUT/.config" -e VT -e VT_CONSOLE -e HW_CONSOLE \
    -e FRAMEBUFFER_CONSOLE -e FRAMEBUFFER_CONSOLE_DETECT_PRIMARY
"$SOURCE/scripts/config" --file "$OUTPUT/.config" --set-str LOCALVERSION "-cloudbook-android14-usb"
make -C "$SOURCE" O="$OUTPUT" ARCH=arm64 LLVM=1 olddefconfig
grep -q '^CONFIG_USB_STORAGE=y$' "$OUTPUT/.config"
grep -q '^CONFIG_DRM_PANEL_EDP=y$' "$OUTPUT/.config"
grep -q '^CONFIG_FRAMEBUFFER_CONSOLE=y$' "$OUTPUT/.config"
make -C "$SOURCE" O="$OUTPUT" ARCH=arm64 LLVM=1 -j"$(nproc)" Image dtbs
cp "$OUTPUT/arch/arm64/boot/Image" /work/artifacts/android14/Image-6.1.172-cloudbook-usb
cp "$OUTPUT/arch/arm64/boot/dts/rockchip/rk3568-ecloudbook-air-24rtw.dtb" /work/artifacts/android14/rk3568-ecloudbook-air-24rtw-6.1-usb.dtb
cp "$OUTPUT/.config" /work/artifacts/android14/config-6.1.172-android14-usb

