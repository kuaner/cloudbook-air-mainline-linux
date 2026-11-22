#!/usr/bin/env python3
"""
Pack the Android 14 boot.img for the ZTE CloudBook Air (CT2000-24RTW / w620d).

Inputs:
  * Kernel Image built from the Rockchip 6.1 BSP (board DTS + MT7663S/BT/SBS/SC2021).
  * FriendlyElec Android 14 ramdisk (first-stage init, Android init).
  * Factory RSCE resource image (contains the w620d board entry selected by SARADC).
  * Board DTB compiled from the vendor 6.1 tree.

The RSCE resource entry for w620d is only 142271 bytes in the factory image;
our 6.1 DTB is larger, so the DTB is appended at a new 512-byte aligned offset
and both entry 0 (rk-kernel.dtb) and the w620d entry are repointed to it.
Per-entry SHA-1 and size are updated, matching what U-Boot verifies.

Android boot image v2 layout is unchanged: header + kernel + ramdisk + second
+ optional dtb segment.  The factory bootloader does not require a non-zero
id[] hash for boot_a (the project's existing Linux images boot with zeros).
"""

import argparse
import hashlib
import struct
from pathlib import Path

PAGE = 2048
BLOCK = 512
HDR_V2_SIZE = 1660
DTB_ADDR = 0x11F00000
OS_VERSION = 0x1C000196  # Android 14, from the FriendlyElec image header


def align_up(value: int, align: int = BLOCK) -> int:
    return (value + align - 1) & ~(align - 1)


def pad_page(data: bytes, page: int = PAGE) -> bytes:
    rem = len(data) % page
    return data + (b"\x00" * (page - rem) if rem else b"")


def find_entries(res: bytes):
    count = struct.unpack_from("<I", res, 12)[0]
    entries = []
    for idx in range(count):
        off = (1 + idx) * BLOCK
        if res[off:off + 4] != b"ENTR":
            raise ValueError(f"entry {idx} at 0x{off:x} is not ENTR")
        name = res[off + 4:off + 224].split(b"\0")[0].decode("utf-8", "replace")
        sha1 = res[off + 224:off + 244]
        tag, blk_off, size, resv = struct.unpack_from("<4I", res, off + 256)
        entries.append({
            "idx": idx, "off": off, "name": name, "sha1": sha1,
            "tag": tag, "blk_off": blk_off, "size": size, "res": resv,
        })
    return entries


def repack_resource(template: bytes, dtb: bytes) -> bytes:
    entries = find_entries(template)

    board_idx = next((e["idx"] for e in entries if "w620d" in e["name"].lower()), None)
    if board_idx is None:
        raise ValueError("w620d board entry not found in the factory resource image")

    out = bytearray(template)
    new_off = align_up(len(out), BLOCK)
    if new_off > len(out):
        out.extend(b"\x00" * (new_off - len(out)))
    out.extend(dtb)
    out.extend(b"\x00" * (align_up(len(out), BLOCK) - len(out)))

    dtb_sha1 = hashlib.sha1(dtb).digest()
    for idx in sorted({0, board_idx}):
        e = entries[idx]
        struct.pack_into("<4I", out, e["off"] + 256,
                         e["tag"], new_off // BLOCK, len(dtb), e["res"])
        out[e["off"] + 224:e["off"] + 244] = dtb_sha1
        print(f"  entry {idx:2d} {e['name'][:58]:60s} "
              f"{e['size']} -> {len(dtb)} bytes @ 0x{new_off:x}")

    return bytes(out)


def build_boot(kernel: bytes, ramdisk: bytes, second: bytes, dtb: bytes,
               cmdline: str) -> bytes:
    header = bytearray(PAGE)
    struct.pack_into(
        "<8s10I", header, 0,
        b"ANDROID!",
        len(kernel), 0x10008000,
        len(ramdisk), 0x11000000,
        len(second), 0x10F00000,
        0x10000100, PAGE, 2, OS_VERSION,
    )
    cmd = cmdline.encode("ascii")[:511]
    header[64:64 + len(cmd)] = cmd

    # v2 extra fields: recovery_dtbo(0), recovery_dtbo_offset(0),
    # header_size(1660), dtb_size, dtb_addr.
    struct.pack_into("<IQIIIQ", header, 1632, 0, 0, HDR_V2_SIZE, 0, len(dtb), DTB_ADDR)

    image = (bytes(header) + pad_page(kernel) + pad_page(ramdisk) +
             pad_page(second) + pad_page(dtb))
    return image


def usb_cmdline(cmdline: str) -> str:
    tokens = [token for token in cmdline.split()
              if not token.startswith(("console=", "androidboot.boot_devices="))]
    tokens.extend([
        "console=tty0",
        "console=ttyS2,115200n8",
        "loglevel=8",
        "ignore_loglevel",
        "no_console_suspend",
        # AOSP GetBootDevices() treats this list as comma-separated.  Keep
        # usbhost first: it is the dedicated host port used by the USB disk.
        "androidboot.boot_devices=usbhost,fd880000.usb,usbdrd",
    ])
    return " ".join(tokens)


def main():
    root = Path(__file__).resolve().parent.parent
    ap = argparse.ArgumentParser()
    ap.add_argument("--kernel", required=True)
    ap.add_argument("--ramdisk", default="/tmp/fee-ramdisk.img")
    ap.add_argument("--dtb", required=True)
    ap.add_argument("--resource-template",
                    default=str(root / "artifacts/extracted_resource.img"))
    ap.add_argument("--cmdline", default=None,
                    help="default: the FriendlyElec Android 14 boot.img cmdline")
    ap.add_argument("--add-console", default="console=ttyS2,115200n8",
                    help="append a serial console for bring-up (empty to disable)")
    ap.add_argument("--output", required=True)
    ap.add_argument("--usb", action="store_true",
                    help="enable tty0 diagnostics and create USB partition device links")
    args = ap.parse_args()

    kernel = Path(args.kernel).read_bytes()
    ramdisk = Path(args.ramdisk).read_bytes()
    dtb = Path(args.dtb).read_bytes()
    template = Path(args.resource_template).read_bytes()

    if args.cmdline is None:
        base = Path("/tmp/fee-android14/android14/boot.img")
        if not base.exists():
            raise SystemExit("Friendly boot.img not found; pass --cmdline explicitly")
        hdr = base.read_bytes()[:PAGE]
        cmdline = hdr[64:64 + 512].split(b"\0")[0].decode("ascii")
    else:
        cmdline = args.cmdline
    if args.add_console:
        cmdline = cmdline + " " + args.add_console
    if args.usb:
        cmdline = usb_cmdline(cmdline)

    print("[i] repacking RSCE resource")
    second = repack_resource(template, dtb)
    boot = build_boot(kernel, ramdisk, second, dtb, cmdline)
    Path(args.output).write_bytes(boot)

    print(f"[OK] {args.output}: {len(boot) / (1024 * 1024):.2f} MB")
    print(f"     kernel  {len(kernel):>9} bytes")
    print(f"     ramdisk {len(ramdisk):>9} bytes")
    print(f"     second  {len(second):>9} bytes")
    print(f"     dtb     {len(dtb):>9} bytes")
    print(f"     cmdline: {cmdline}")


if __name__ == "__main__":
    main()
