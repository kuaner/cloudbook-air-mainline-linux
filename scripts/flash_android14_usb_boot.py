#!/usr/bin/env python3
"""Install only Android 14 boot_a and a three-try misc on the CloudBook Air."""

import argparse
import binascii
import hashlib
from pathlib import Path
import struct
import subprocess
import time


BOOT_BYTES = 160 * 1024 * 1024
MISC_BYTES = 4 * 1024 * 1024


def adb(*args, stdout=None, capture=False):
    command = ["adb", *args]
    if stdout is None and not capture:
        subprocess.run(command, check=True)
        return None
    return subprocess.run(command, check=True, stdout=subprocess.PIPE if capture else stdout).stdout


def shell(command):
    return adb("shell", command, capture=True).decode().strip()


def read_partition(name, size):
    data = adb("exec-out", f"dd if=/dev/block/by-name/{name} bs={size} count=1 status=none",
               capture=True)
    if len(data) != size:
        raise RuntimeError(f"Short read from {name}: {len(data)} != {size}")
    return data


def write_partition(name, image, size):
    if len(image) != size:
        raise RuntimeError(f"Wrong image size for {name}: {len(image)} != {size}")
    subprocess.run(["adb", "shell", f"dd of=/dev/block/by-name/{name} bs={size} count=1 status=none"],
                   input=image, check=True)
    shell("sync")
    readback = read_partition(name, size)
    if readback != image:
        raise RuntimeError(f"Readback verification failed for {name}")
    print(f"Verified {name}: {hashlib.sha256(image).hexdigest()}", flush=True)


def slot_data(misc):
    if len(misc) != MISC_BYTES or misc[0x800:0x804] != b"\0AB0":
        raise RuntimeError("No valid AvbABData in misc")
    raw = misc[0x800:0x81c]
    stored_crc = struct.unpack_from(">I", misc, 0x81c)[0]
    if binascii.crc32(raw) & 0xffffffff != stored_crc:
        raise RuntimeError("Invalid AvbABData CRC")
    return raw


def trial_misc(misc):
    slot_data(misc)
    output = bytearray(misc)
    body = struct.pack("<4sBB2xBBBxBBBx12x", b"\0AB0", 1, 0,
                       15, 3, 0, 14, 0, 1)
    output[0x800:0x820] = body + struct.pack(">I", binascii.crc32(body) & 0xffffffff)
    return bytes(output)


def check_device(factory_boot_b):
    adb("wait-for-device")
    adb("root")
    time.sleep(2)
    adb("wait-for-device")
    if shell("id -u") != "0":
        raise RuntimeError("ADB root is required")
    if shell("getprop ro.product.vendor.device") != "rk3568_r_w600d2":
        raise RuntimeError("This is not the CloudBook rk3568_r_w600d2")
    if shell("getprop ro.boot.slot_suffix") != "_b":
        raise RuntimeError("Refusing to write while Android is running from A")
    current_b = read_partition("boot_b", BOOT_BYTES)
    if current_b != factory_boot_b.read_bytes():
        raise RuntimeError("Factory B backup does not match the connected device")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--boot", type=Path, required=True)
    parser.add_argument("--factory-boot-b", type=Path,
                        default=Path(__file__).resolve().parent.parent / "factory_backup/boot_b.img")
    parser.add_argument("--write", action="store_true", help="perform writes; otherwise only read and check")
    parser.add_argument("--reboot", action="store_true", help="reboot only after both images are verified")
    parser.add_argument("--output-dir", type=Path, default=Path("artifacts/android14/flash"))
    args = parser.parse_args()

    boot = args.boot.read_bytes()
    if len(boot) > BOOT_BYTES or boot[:8] != b"ANDROID!":
        raise RuntimeError("Boot image must be Android boot format and at most 160 MiB")
    boot = boot.ljust(BOOT_BYTES, b"\0")
    check_device(args.factory_boot_b)

    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "boot_a-before.img").write_bytes(read_partition("boot_a", BOOT_BYTES))
    misc = read_partition("misc", MISC_BYTES)
    (args.output_dir / "misc-before.img").write_bytes(misc)
    print("Current AvbABData:", slot_data(misc)[8:14].hex())
    print("Preflight passed; eMMC super, userdata, boot_b and bootloader are not written.")
    if not args.write:
        return

    write_partition("boot_a", boot, BOOT_BYTES)
    write_partition("misc", trial_misc(misc), MISC_BYTES)
    (args.output_dir / "misc-trial-a.img").write_bytes(trial_misc(misc))
    if args.reboot:
        adb("reboot")


if __name__ == "__main__":
    main()
