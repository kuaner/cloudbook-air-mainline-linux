#!/usr/bin/env python3
"""Build the CloudBook Android 14 USB first-stage ramdisk without a device write."""

import argparse
import gzip
import hashlib
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile


PAGE = 2048


def run(command, **kwargs):
    return subprocess.run(command, check=True, **kwargs)


def boot_ramdisk(path):
    data = path.read_bytes()
    if data[:8] != b"ANDROID!":
        raise ValueError(f"Not an Android boot image: {path}")
    kernel_size = struct.unpack_from("<I", data, 8)[0]
    ramdisk_size = struct.unpack_from("<I", data, 16)[0]
    page = struct.unpack_from("<I", data, 36)[0]
    offset = page + (kernel_size + page - 1) // page * page
    ramdisk = data[offset:offset + ramdisk_size]
    if gzip.decompress(ramdisk)[:6] not in (b"070701", b"070702"):
        raise ValueError("Expected a gzip-compressed newc ramdisk")
    return ramdisk


def extract(source, target, path):
    output = target / path
    output.parent.mkdir(parents=True, exist_ok=True)
    run(["debugfs", "-R", f"dump /{path} {output}", str(source)],
        stdout=subprocess.DEVNULL)
    if not output.is_file() or not output.stat().st_size:
        raise ValueError(f"Cannot extract {path} from {source}")
    output.chmod(0o755 if "/bin/" in path else 0o644)


def archive(root, output):
    listing = []
    for path in sorted(root.rglob("*")):
        if path == root:
            continue
        relative = path.relative_to(root).as_posix()
        stat = path.lstat()
        mode, ino, nlink = stat.st_mode, stat.st_ino, stat.st_nlink
        data = os.readlink(path).encode() if path.is_symlink() else (
            path.read_bytes() if path.is_file() else b"")
        listing.append((relative, stat, data, len(data), path.is_file() and nlink > 1))
    with output.open("wb") as stream:
        for relative, stat, data, size, _ in listing:
            fields = [stat.st_ino & 0xffffffff, stat.st_mode, stat.st_uid, stat.st_gid, stat.st_nlink,
                      int(stat.st_mtime), size, 0, 0, 0, 0, len(relative.encode()) + 1, 0]
            stream.write(("070701" + "".join(f"{field:08x}" for field in fields)).encode())
            stream.write(relative.encode() + b"\0")
            stream.write(b"\0" * (-stream.tell() % 4))
            stream.write(data)
            stream.write(b"\0" * (-stream.tell() % 4))
        trailer = "070701" + "".join(f"{field:08x}" for field in
                                     [0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 11, 0])
        stream.write(trailer.encode() + b"TRAILER!!!\0" + b"\0" * 3)
        stream.write(b"\0" * (-stream.tell() % 4))


def set_metadata(path, mode=None, uid=0, gid=0):
    path.chmod(path.stat().st_mode if mode is None else mode)
    os.chown(path, uid, gid)
    os.utime(path, (0, 0), follow_symlinks=False)


def build(source_boot, system, overlay, output):
    original_ramdisk = boot_ramdisk(source_boot)
    original_hash = hashlib.sha256(original_ramdisk).hexdigest()
    original_uncompressed = gzip.decompress(original_ramdisk)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="cb-android14-ramdisk-") as temporary:
        temp = Path(temporary)
        raw = temp / "original.cpio"
        raw.write_bytes(original_uncompressed)
        tree = temp / "tree"
        tree.mkdir()
        run(["sh", "-c", "cpio -idmu --quiet < \"$1\"", "sh", raw], cwd=tree)
        for name in ("e2fsck", "resize2fs"):
            extract(system, tree, f"system/bin/{name}")
        for name in ("libext2_blkid.so", "libext2_com_err.so", "libext2_e2p.so",
                     "libext2_misc.so", "libext2_quota.so", "libext2_uuid.so", "libext2fs.so"):
            extract(system, tree, f"system/lib64/{name}")
        for name in ("libsparse.so", "libz.so", "liblog.so", "libselinux.so", "libprocessgroup.so"):
            extract(system, tree, f"system/lib64/{name}")
        for name, destination in (
            ("fstab.rk30board", "first_stage_ramdisk/fstab.rk30board"),
            ("init.cloudbook-usb.rc", "system/etc/init/init.cloudbook-usb.rc"),
            ("cloudbook-usb-diagnostics.sh", "system/etc/init/cloudbook-usb-diagnostics.sh"),
        ):
            shutil.copy2(overlay / name, tree / destination)
        for directory in tree.rglob("*"):
            if directory.is_symlink():
                continue
            elif directory.is_dir():
                set_metadata(directory)
            else:
                set_metadata(directory, 0o755 if directory.stat().st_mode & 0o111 else 0o644)
        set_metadata(tree / "first_stage_ramdisk/fstab.rk30board", 0o644)
        set_metadata(tree / "system/etc/init/cloudbook-usb-diagnostics.sh", 0o750, 0, 2000)
        for path in (
            "system/bin/init", "system/bin/sh", "system/bin/mke2fs", "system/bin/e2fsdroid",
            "system/bin/e2fsck", "system/bin/resize2fs",
        ):
            set_metadata(tree / path, 0o750, 0, 2000)
        for path in (tree / "first_stage_ramdisk/system").rglob("*"):
            if not path.is_dir():
                set_metadata(path, path.stat().st_mode, 0, 2000)
        raw.unlink()
        archive(tree, raw)
        with raw.open("rb") as src, output.open("wb") as dst:
            with gzip.GzipFile(filename="", mode="wb", fileobj=dst, mtime=0) as gz:
                shutil.copyfileobj(src, gz)
        digest = hashlib.sha256(output.read_bytes()).hexdigest()
        print(f"Original ramdisk: {original_hash}")
        print(f"USB ramdisk: {digest} ({output.stat().st_size} bytes)")
        (output.parent / "SOURCE_RAMDISK.sha256").write_text(original_hash + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-boot", type=Path, required=True)
    parser.add_argument("--system", type=Path, required=True)
    parser.add_argument("--overlay", type=Path,
                        default=Path(__file__).resolve().parent.parent / "android14/usb")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    build(args.source_boot, args.system, args.overlay, args.output)


if __name__ == "__main__":
    main()

