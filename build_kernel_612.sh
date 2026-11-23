#!/usr/bin/env bash
# Build a minimal, reproducible Linux 6.12 candidate without touching the device.
set -euo pipefail

ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
SRC_DIR="$ROOT_DIR/linux-6.12"
OUT_DIR="$ROOT_DIR/kernel_build/out-6.12"
DTS_NAME=rk3568-ecloudbook-air-24rtw
DTS_SRC=${DTS_SRC:-"$ROOT_DIR/mainline-6.12/rk3568-ecloudbook-air-24rtw-v30-charging-led.dts"}
JOBS=${JOBS:-$(nproc)}
BUILD_MODULES=${BUILD_MODULES:-0}

apply_patch_if_missing() {
    local marker=$1
    local target_file=$2
    local patch_file=$3
    if ! grep -q "$marker" "$SRC_DIR/$target_file"; then
        if command -v git >/dev/null 2>&1; then
            # git apply parses git-format diffs strictly; the patches in
            # mainline-6.12/patches are validated against a pristine 6.12.117
            # tree, so no fuzz/offset handling is needed.
            # A source tarball inside this repo must be its own patch root.
            GIT_CEILING_DIRECTORIES="$ROOT_DIR" git -C "$SRC_DIR" apply --whitespace=nowarn \
                "$ROOT_DIR/mainline-6.12/patches/$patch_file"
        else
            patch -d "$SRC_DIR" -p1 --forward < "$ROOT_DIR/mainline-6.12/patches/$patch_file"
        fi
    fi
}

for tool in clang ld.lld llvm-ar llvm-nm llvm-objcopy make dtc; do
    command -v "$tool" >/dev/null || { echo "Missing required tool: $tool" >&2; exit 1; }
done
mkdir -p "$OUT_DIR"
if [[ ! -f "$OUT_DIR/.config" ]]; then
    # Start from the configuration validated on the device instead of a plain
    # defconfig, so a fresh clone rebuilds the same kernel that was boot tested.
    if [[ -f "$ROOT_DIR/mainline-6.12/config-6.12.117-cloudbook" ]]; then
        cp "$ROOT_DIR/mainline-6.12/config-6.12.117-cloudbook" "$OUT_DIR/.config"
    else
        make -C "$SRC_DIR" O="$OUT_DIR" ARCH=arm64 LLVM=1 defconfig
    fi
fi

# Sync layered v* DTS sources so #include chains (v9 -> ... -> v23) resolve inside Kbuild.
cp "$ROOT_DIR/mainline-6.12/"rk3568-ecloudbook-air-24rtw*.dts \
   "$SRC_DIR/arch/arm64/boot/dts/rockchip/"
cp "$DTS_SRC" \
   "$SRC_DIR/arch/arm64/boot/dts/rockchip/$DTS_NAME.dts"
# Register the two board DTBs explicitly: a direct "make rockchip/foo.dtb"
# target only exists when the DTB is listed in this Makefile.
for d in "$DTS_NAME" "$DTS_NAME-v28-usb2-pm-fix"; do
    MK="$SRC_DIR/arch/arm64/boot/dts/rockchip/Makefile"
    grep -qF "+= $d.dtb" "$MK" || \
        sed -i "1a dtb-\$(CONFIG_ARCH_ROCKCHIP) += $d.dtb" "$MK"
done


# Apply required P3/P4 kernel patches (0002 is diagnostic-only; 0004 fixed-1v8 is bypassed by v23 CMD11).
apply_patch_if_missing 'USB_DEVICE(0x0fe6, 0x9900)' "drivers/net/usb/r8152.c" "0001-r8152-add-sr9900-id.patch"
apply_patch_if_missing '659000000' "drivers/clk/rockchip/clk-rk3568.c" "0003-clk-rk3568-add-659mhz-vpll-rate.patch"
apply_patch_if_missing 'obj-$(CONFIG_MT7663S) += mt7663s/' "drivers/net/wireless/mediatek/mt76/Makefile" "0005-mt76-mt7663s-w103d-isolated.patch"
apply_patch_if_missing 'btmtksdio-w103d.h' "drivers/bluetooth/btmtksdio.c" "0006-mt7663-w103d-combo-lifecycle.patch"
apply_patch_if_missing "Keep the station's data bitrate from being replaced" "net/mac80211/tx.c" "0007-mac80211-data-rate-reporting.patch"
apply_patch_if_missing 'CHARGER_SC2021' "drivers/power/supply/Kconfig" "0008-power-supply-sc2021-bq25703a.patch"
apply_patch_if_missing 'PHY_ROCKCHIP_NANENG_EDP' "drivers/phy/rockchip/Kconfig" "0009-phy-rockchip-naneng-edp.patch"
apply_patch_if_missing 'RK3568_EDP' "drivers/gpu/drm/rockchip/analogix_dp-rockchip.c" "0010-drm-analogix-dp-rk3568-and-boe-nv140wum-n44.patch"
apply_patch_if_missing 'mt7663s_w103d_sanitize_wiphy' "drivers/net/wireless/mediatek/mt76/mt7663s/mt7663s_w103d.c" "0011-mt76-mt7663s-w103d-sanitize-wiphy.patch"
apply_patch_if_missing 'mt7663s_w103d_vendor_disconnect_bss' "drivers/net/wireless/mediatek/mt76/mt7663s/mt7663s_w103d.c" "0012-mt76-mt7663s-w103d-fix-band-switch-disconnect.patch"
apply_patch_if_missing 'mt7663s_w103d_sync_vendor_connect' "drivers/net/wireless/mediatek/mt76/mt7663s/mt7663s_w103d.c" "0013-mt76-mt7663s-w103d-tx-ampdu-and-sdio-throughput.patch"
apply_patch_if_missing 'sbs_resolve_status' "drivers/power/supply/sbs-battery.c" "0014-power-supply-sbs-align-charging-led-status.patch"
apply_patch_if_missing 'rockchip_dp_aux_done_probing' "drivers/gpu/drm/rockchip/analogix_dp-rockchip.c" "0014-drm-rockchip-probe-edp-panel-on-aux-bus.patch"
apply_patch_if_missing 'use_gregorian_calendar' "drivers/rtc/rtc-rk808.c" "0015-rtc-rk808-use-gregorian-calendar-on-cloudbook-air.patch"

# Keep storage, UART, USB Rootfs, PMIC and display support built into the kernel;
# keep P4 peripheral drivers (=y/=m) aligned with config-6.12.117-cloudbook.
"$SRC_DIR/scripts/config" --file "$OUT_DIR/.config" \
    --set-str LOCALVERSION "-cloudbook" \
    --set-val PANIC_TIMEOUT 10 \
    -e ARCH_ROCKCHIP -e MODULES \
    -d KVM -d BTRFS_FS -d NFS_FS -d NFSD -d CIFS -d UBIFS \
    -d DRM_NOUVEAU -d DRM_AMDGPU -d DRM_RADEON -d DRM_I915 -d DRM_XE \
    -d XEN \
    -e WLAN -e WLAN_VENDOR_MEDIATEK -m CFG80211 -m MAC80211 \
    -m MT76_CORE -m MT76_SDIO -m MT7615_COMMON -m MT7663S -e MT7663S_W103D_COMBO \
    -m BT -m BT_MTK -m BT_MTKSDIO \
    -d ARCH_ACTIONS -d ARCH_AIROHA -d ARCH_SUNXI -d ARCH_ALPINE -d ARCH_APPLE -d ARCH_BCM -d ARCH_BCM2835 -d ARCH_BCM_IPROC -d ARCH_BCMBCA -d ARCH_BRCMSTB -d ARCH_BERLIN -d ARCH_EXYNOS -d ARCH_SPARX5 -d ARCH_K3 -d ARCH_LG1K -d ARCH_HISI -d ARCH_KEEMBAY -d ARCH_MEDIATEK -d ARCH_MESON -d ARCH_MVEBU -d ARCH_NXP -d ARCH_LAYERSCAPE -d ARCH_MXC -d ARCH_S32 -d ARCH_MA35 -d ARCH_NPCM -d ARCH_QCOM -d ARCH_REALTEK -d ARCH_RENESAS -d ARCH_SEATTLE -d ARCH_INTEL_SOCFPGA -d ARCH_STM32 -d ARCH_SYNQUACER -d ARCH_TEGRA -d ARCH_TESLA_FSD -d ARCH_SPRD -d ARCH_THUNDER -d ARCH_THUNDER2 -d ARCH_UNIPHIER -d ARCH_VEXPRESS -d ARCH_VISCONTI -d ARCH_XGENE -d ARCH_ZYNQMP \
    -e DEVTMPFS -e DEVTMPFS_MOUNT -e TMPFS -e EXT4_FS \
    -e BLOCK -e BLK_DEV_SD -e SCSI \
    -e SERIAL_8250 -e SERIAL_8250_CONSOLE -e SERIAL_8250_DW \
    -e MMC -e MMC_BLOCK -e MMC_SDHCI -e MMC_SDHCI_PLTFM -e MMC_SDHCI_OF_DWCMSHC \
    -e USB -e USB_XHCI_HCD -e USB_XHCI_PLATFORM \
    -e USB_EHCI_HCD -e USB_EHCI_HCD_PLATFORM \
    -e USB_OHCI_HCD -e USB_OHCI_HCD_PLATFORM \
    -e USB_STORAGE -e USB_USBNET -e USB_RTL8152 -e USB_HID \
    -e USB_NET_CDCETHER -e USB_NET_CDC_NCM -e USB_NET_AX88179_178A -e USB_NET_DM9601 \
    -e SCSI_LOWLEVEL -e USB_COMMON \
    -e I2C -e I2C_RK3X -e I2C_HID_CORE -e I2C_HID_OF \
    -e INPUT_EVDEV -e KEYBOARD_GPIO \
    -e MFD_RK8XX -e MFD_RK8XX_I2C -e REGULATOR_RK808 -e RTC_DRV_RK808 \
    -e REGULATOR -e REGULATOR_FIXED_VOLTAGE \
    -e COMMON_CLK_ROCKCHIP -e ROCKCHIP_PM_DOMAINS -e PINCTRL_ROCKCHIP \
    -e PHY_ROCKCHIP_INNO_USB2 -e PHY_ROCKCHIP_NANENG_COMBO_PHY \
    -e PHY_ROCKCHIP_NANENG_EDP \
    -e DRM -e DRM_KMS_HELPER -e DRM_DISPLAY_HELPER \
    -e DRM_TTM -e DRM_TTM_HELPER -e DRM_GEM_DMA_HELPER \
    -e DRM_DISPLAY_DP_AUX_BUS -e DRM_DISPLAY_DP_HELPER \
    -e DRM_FBDEV_EMULATION -e FRAMEBUFFER_CONSOLE \
    -e DRM_ROCKCHIP -e ROCKCHIP_ANALOGIX_DP \
    -e PWM -e BACKLIGHT_CLASS_DEVICE \
    -e DRM_PANEL_EDP -e DRM_PANEL_SIMPLE -e BACKLIGHT_PWM -e PWM_ROCKCHIP \
    -m DRM_PANFROST -e DRM_DW_HDMI -e ROCKCHIP_DW_HDMI \
    -m DRM_DW_HDMI_I2S_AUDIO -m DRM_DW_HDMI_CEC \
    -e SND -e SND_SOC -m SND_SIMPLE_CARD -m SND_SOC_ROCKCHIP \
    -m SND_SOC_ROCKCHIP_I2S_TDM -m SND_SOC_ROCKCHIP_PDM -m SND_SOC_RK817 -m SND_SOC_DMIC \
    -m HID_MULTITOUCH -m UHID -e BATTERY_SBS -m CHARGER_RK817 \
    -e CHARGER_BQ25700 -e CHARGER_SC2021 \
    -m ROCKCHIP_THERMAL -m ROCKCHIP_SARADC -m USB_VIDEO_CLASS \
    -m VIDEO_HANTRO -e VIDEO_HANTRO_ROCKCHIP -m VIDEO_ROCKCHIP_RGA \
    -e WATCHDOG -e WATCHDOG_CORE -e WATCHDOG_HANDLE_BOOT_ENABLED -e DW_WDT \
    -e PM_DEBUG -e PM_ADVANCED_DEBUG -e PM_SLEEP_DEBUG \
    -e SOFTLOCKUP_DETECTOR -e DETECT_HUNG_TASK -e WQ_WATCHDOG

make -C "$SRC_DIR" O="$OUT_DIR" ARCH=arm64 LLVM=1 olddefconfig
make -C "$SRC_DIR" O="$OUT_DIR" ARCH=arm64 LLVM=1 -j"$JOBS" \
    DTC_FLAGS="-@" Image dtbs
if [[ "$BUILD_MODULES" == "1" ]]; then
    make -C "$SRC_DIR" O="$OUT_DIR" ARCH=arm64 LLVM=1 -j"$JOBS" modules
fi

cp "$OUT_DIR/.config" "$ROOT_DIR/mainline-6.12/config-6.12.117-cloudbook"
sha256sum \
    "$OUT_DIR/arch/arm64/boot/Image" \
    "$OUT_DIR/arch/arm64/boot/dts/rockchip/$DTS_NAME.dtb" \
    "$ROOT_DIR/mainline-6.12/config-6.12.117-cloudbook"
