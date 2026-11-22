#!/usr/bin/env python3
"""
Rockchip Boot Image & RSCE Resource Packer for ZTE CloudBook Air CT2000-24RTW
Packs Mainline/Armbian ARM64 Kernel (Image), initramfs (initrd.img), and
compiled DTB (rk3568-ecloudbook-air-24rtw.dtb) into a boot_linux.img
ready to flash to Slot A (/dev/block/by-name/boot_a).

Embeds into the RSCE second_stage (resource.img):
  - rk-kernel.dtb (with valid SHA-1 hash)
  - rk3568-w620d-25wh_v12#_saradc_ch1=938#_saradc_ch4=680.dtb (with valid SHA-1 hash)
  - Factory BMP logos and battery icons

Usage:
  python3 pack_boot_linux.py \
      --kernel kernel_build/out-6.12/arch/arm64/boot/Image \
      --ramdisk artifacts/initramfs-6.12.117-cloudbook-emmc.img \
      --dtb kernel_build/out-6.12/arch/arm64/boot/dts/rockchip/rk3568-ecloudbook-air-24rtw.dtb \
      --resource-template artifacts/extracted_resource.img \
      --output artifacts/boot_linux_612_v29_emmc.img
"""

import argparse
import hashlib
import struct
from pathlib import Path

BLOCK_SIZE = 512
PAGE_SIZE = 2048
BOOT_PARTITION_SIZE = 160 * 1024 * 1024

def align_up(val: int, align: int = PAGE_SIZE) -> int:
    return (val + align - 1) & ~(align - 1)

def pad_page(b: bytes, page: int = PAGE_SIZE) -> bytes:
    rem = len(b) % page
    return b + (b"\x00" * (page - rem) if rem else b"")

def find_board_entry(res_data: bytearray) -> int:
    """Locate the board-identification DTB entry by name.

    The resource table order differs between factory backups and OTA
    packages, so the entry is matched by the board name (e.g.
    rk3568-w620d-25wh_v12#_saradc_ch1=938#_saradc_ch4=680) instead of a
    hardcoded index. Falls back to 17 for the original factory layout.
    """
    for idx in range(1, 64):
        entr_off = (1 + idx) * BLOCK_SIZE
        if res_data[entr_off:entr_off + 4] != b"ENTR":
            break
        name = bytes(res_data[entr_off + 4:entr_off + 4 + 220]).split(b"\0")[0]
        if b"w620d" in name.lower():
            return idx
    return 17


def patch_resource_img(template_bytes: bytes, dtb_bytes: bytes) -> bytes:
    """Patch factory resource image with custom DTB and exact SHA-1 hashes for board detection."""
    res_data = bytearray(template_bytes)
    dtb_sha1 = hashlib.sha1(dtb_bytes).digest()

    # Entry 0 (rk-kernel.dtb) plus the board entry that U-Boot selects by ADC.
    board_idx = find_board_entry(res_data)
    board_name = bytes(res_data[(1 + board_idx) * BLOCK_SIZE + 4:
                                (1 + board_idx) * BLOCK_SIZE + 4 + 220]).split(b"\0")[0]
    print(f"[i] Patching resource entries 0 and {board_idx} ({board_name.decode('utf8', 'replace')})")
    for idx in sorted({0, board_idx}):
        entr_off = (1 + idx) * BLOCK_SIZE
        assert res_data[entr_off:entr_off + 4] == b"ENTR", f"Entry {idx} is not ENTR"
        tag, blk_off, old_sz, res = struct.unpack_from("<4I", res_data, entr_off + 256)
        data_off = blk_off * BLOCK_SIZE
        if data_off + old_sz > len(res_data):
            raise ValueError(f"Resource entry {idx} exceeds the template size")
        if len(dtb_bytes) > old_sz:
            # RSCE entries locate their payload by sector. Allocate a new
            # extent so a larger DTB cannot overwrite the next board or logo.
            data_off = align_up(len(res_data), BLOCK_SIZE)
            end_off = data_off + align_up(len(dtb_bytes), BLOCK_SIZE)
            res_data.extend(bytes(end_off - len(res_data)))
            struct.pack_into("<I", res_data, entr_off + 260, data_off // BLOCK_SIZE)

        # Write DTB bytes
        res_data[data_off:data_off + len(dtb_bytes)] = dtb_bytes
        if old_sz > len(dtb_bytes):
            res_data[data_off + len(dtb_bytes):data_off + old_sz] = b"\x00" * (old_sz - len(dtb_bytes))

        # Update size and SHA-1 in ENTR block
        struct.pack_into("<I", res_data, entr_off + 256 + 8, len(dtb_bytes))
        res_data[entr_off + 224:entr_off + 224 + 20] = dtb_sha1

    return bytes(res_data)

def build_boot_img(kernel_bytes: bytes, ramdisk_bytes: bytes, resource_bytes: bytes, dtb_bytes: bytes, cmdline: str) -> bytes:
    k_addr = 0x10008000
    r_addr = 0x11000000
    s_addr = 0x10F00000
    tags_addr = 0x10000100
    dtb_addr = 0x11F00000
    os_ver = 0x16000157

    header = bytearray(PAGE_SIZE)
    struct.pack_into(
        "<8s10I",
        header, 0,
        b"ANDROID!",
        len(kernel_bytes), k_addr,
        len(ramdisk_bytes), r_addr,
        len(resource_bytes), s_addr,
        tags_addr, PAGE_SIZE, 2, os_ver
    )
    cmd_bytes = cmdline.encode("ascii")[:511]
    header[64:64 + len(cmd_bytes)] = cmd_bytes

    # Android Boot Image v2 fields:
    # offset 1632: recovery_dtbo_size (uint32, 0)
    # offset 1636: recovery_dtbo_offset (uint64, 0)
    # offset 1644: header_size (uint32, 1660)
    # offset 1648: dtb_size (uint32)
    # offset 1652: dtb_addr (uint64)
    struct.pack_into(
        "<IQIIQ",
        header, 1632,
        0, 0, 1660, len(dtb_bytes), dtb_addr
    )

    boot_bytes = (bytes(header) +
                  pad_page(kernel_bytes) +
                  pad_page(ramdisk_bytes) +
                  pad_page(resource_bytes) +
                  pad_page(dtb_bytes))
    if len(boot_bytes) > BOOT_PARTITION_SIZE:
        raise ValueError(
            f"Boot image exceeds the 160 MiB boot partition: "
            f"{len(boot_bytes)} > {BOOT_PARTITION_SIZE} bytes"
        )
    return boot_bytes

def main():
    root_dir = Path(__file__).resolve().parent
    ap = argparse.ArgumentParser()
    ap.add_argument("--kernel", default=str(root_dir / "kernel_build/out-6.12/arch/arm64/boot/Image"), help="Path to ARM64 Image")
    ap.add_argument("--ramdisk", default=str(root_dir / "artifacts/initramfs-6.12.117-cloudbook-emmc.img"), help="Path to initrd.img")
    ap.add_argument("--dtb", default=str(root_dir / "kernel_build/out-6.12/arch/arm64/boot/dts/rockchip/rk3568-ecloudbook-air-24rtw.dtb"), help="Path to DTB")
    ap.add_argument("--resource-template", default=str(root_dir / "artifacts/extracted_resource.img"), help="Path to factory resource.img template")
    # 无串口试启动不能无限等待 Rootfs；挂载失败应 panic 并重启，让 A/B 试启动回退。
    ap.add_argument("--cmdline", default="console=tty0 console=ttyS2,115200n8 root=LABEL=EMMC_ROOTFS rootfstype=ext4 rootwait rw net.ifnames=0 clk_ignore_unused pd_ignore_unused cpuidle.off=1 no_console_suspend", help="Kernel cmdline")
    ap.add_argument("--output", default=str(root_dir / "artifacts/boot_linux.img"), help="Output path")
    args = ap.parse_args()

    k_bytes = Path(args.kernel).read_bytes()
    r_bytes = Path(args.ramdisk).read_bytes()
    d_bytes = Path(args.dtb).read_bytes()
    t_bytes = Path(args.resource_template).read_bytes()

    res_bytes = patch_resource_img(t_bytes, d_bytes)
    boot_bytes = build_boot_img(k_bytes, r_bytes, res_bytes, d_bytes, args.cmdline)
    Path(args.output).write_bytes(boot_bytes)

    print(f"[OK] Built {args.output} ({len(boot_bytes) / (1024*1024):.2f} MB)")
    print(f"     Kernel:   {len(k_bytes)} bytes")
    print(f"     Ramdisk:  {len(r_bytes)} bytes")
    print(f"     Resource: {len(res_bytes)} bytes (with exact SHA-1 & factory logos)")
    print(f"     DTB:      {len(d_bytes)} bytes (v2 dtb section at 0x11F00000)")

if __name__ == "__main__":
    main()
