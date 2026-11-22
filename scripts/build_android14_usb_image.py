#!/usr/bin/env python3
"""Create a GPT USB test image from raw Android 14 partition images."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import os
from pathlib import Path
import struct
import subprocess
import sys
import uuid


SECTOR = 512
FIRST_USABLE = 2048
DISK_UUID = uuid.UUID("af132dc2-1054-4ae1-9d59-965bf2e5036b")
PARTITION_UUIDS = [
    "12345678-1234-4234-8234-000000000001",
    "12345678-1234-4234-8234-000000000002",
    "12345678-1234-4234-8234-000000000003",
    "12345678-1234-4234-8234-000000000004",
    "12345678-1234-4234-8234-000000000005",
    "12345678-1234-4234-8234-000000000006",
    "12345678-1234-4234-8234-000000000007",
    "12345678-1234-4234-8234-000000000008",
    "12345678-1234-4234-8234-000000000009",
    "12345678-1234-4234-8234-00000000000a",
]


def align(value, alignment=SECTOR):
    return (value + alignment - 1) & ~(alignment - 1)


def gpt(layout, total_sectors):
    last_usable = total_sectors - 34
    entries = bytearray()
    for number, (name, start, end) in enumerate(layout):
        encoded = name.encode("utf-16le")[:72].ljust(72, b"\0")
        entry = struct.pack("<16s16sQQQ72s", uuid.UUID("0fc63daf-8483-4772-8e79-3d69d8477de4").bytes_le,
                            uuid.UUID(PARTITION_UUIDS[number]).bytes_le, start, end,
                            1 << 60, encoded)
        entries.extend(entry)
    entries.extend(b"\0" * (128 * 128 - len(entries)))
    count, entries_offset, entries_crc = 128, 2, __import__("zlib").crc32(entries)
    header = struct.pack("<8sIIIIQQQQ16sQIII", b"EFI PART", 0x10000, 92, 0, 0, 1,
        last_usable, FIRST_USABLE, last_usable, DISK_UUID.bytes_le,
                         entries_offset, count, 128, entries_crc).ljust(SECTOR, b"\0")
    header = header[:16] + struct.pack("<I", __import__("zlib").crc32(header[:92])) + header[20:]
    backup = struct.pack("<8sIIIIQQQQ16sQIII", b"EFI PART", 0x10000, 92, 0, 0,
        total_sectors - 1, last_usable, FIRST_USABLE, last_usable,
        DISK_UUID.bytes_le, total_sectors - 33, count, 128, entries_crc).ljust(SECTOR, b"\0")
    backup = backup[:16] + struct.pack("<I", __import__("zlib").crc32(backup[:92])) + backup[20:]
    protective = bytearray(SECTOR)
    protective[446:462] = b"\0\0\x02\0\xee\xff\xff\xff\x01\0\0\0" + struct.pack("<I", min(total_sectors - 1, 0xffffffff))
    protective[510:512] = b"\x55\xaa"
    return protective + header, entries, backup


def copy_partition(source, image_path, start, size, expected):
    digest = hashlib.sha256()
    with source.open("rb") as src, image_path.open("r+b", buffering=0) as dst:
        dst.seek(start * SECTOR)
        remaining = size
        while remaining:
            chunk = src.read(min(4 << 20, remaining))
            if not chunk:
                raise ValueError(f"Short partition: {source}")
            digest.update(chunk)
            dst.write(chunk)
            remaining -= len(chunk)
    if digest.hexdigest() != expected:
        raise ValueError(f"Partition digest differs: {source}")
    return source.name, expected


def build(inputs, output, userdata_bytes, overwrite):
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists() and not overwrite:
        raise ValueError("Refusing to overwrite an existing image; pass --overwrite explicitly")
    rows = [line.split("\t") for line in
            (inputs / "partitions.tsv").read_text().splitlines()
            if line and not line.startswith("#")]
    sources, layout, cursor, expected_hashes = [], [], FIRST_USABLE, {}
    for index, (name, filename) in enumerate(rows):
        if filename == "-":
            size = 256 << 20 if name == "usb_metadata" else userdata_bytes
        else:
            source = inputs / filename
            size = source.stat().st_size
            sources.append((source, len(layout), size))
            expected_hashes[source.name] = hashlib.file_digest(source.open("rb"), "sha256").hexdigest()
        if size % 4096:
            raise ValueError(f"Unaligned partition size: {name}")
        start = cursor
        cursor += size // SECTOR
        end = start + size // SECTOR - 1
        layout.append((name, start, end))
        print(f"{index + 1:2} {name:18} start={start:10} size={size / 1024**2:9.1f} MiB")
    total_sectors = (cursor + 33 + FIRST_USABLE - 1) // FIRST_USABLE * FIRST_USABLE
    image = output.open("w+b")
    image.truncate(total_sectors * SECTOR)
    header, entries, backup = gpt(layout, total_sectors)
    image.write(header)
    image.write(entries)
    image.seek((total_sectors - 33) * SECTOR)
    image.write(entries)
    image.write(backup)
    for name, number, size in sources:
        print("Copying", name.name, flush=True)
    image.close()
    with ThreadPoolExecutor(max_workers=3) as pool:
        results = pool.map(lambda item: copy_partition(item[0], output, item[1], item[2],
                                                       expected_hashes[item[0].name]),
                           [(source, layout[number][1], size) for source, number, size in sources])
    image = output.open("r+b")
    image.flush()
    os.fsync(image.fileno())
    image.close()
    (output.parent / "USB_LAYOUT.txt").write_text("\n".join(
        f"{number:2} {name:18} start={start:10} end={end:10} bytes={(end - start + 1) * SECTOR}"
        for number, (name, start, end) in enumerate(layout, 1)) + "\n")
    for source, digest in results:
        print(source, digest)
    print(output, "GPT written; filesystem images verified")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--inputs", type=Path, default=Path("/tmp/fee-parts"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--userdata-mib", type=int, default=4 * 1024)
    parser.add_argument("--overwrite", action="store_true")
    args = parser.parse_args()
    if not 4096 <= args.userdata_mib <= 56 * 1024:
        parser.error("userdata must be between 4 GiB and 56 GiB")
    build(args.inputs, args.output, args.userdata_mib << 20, args.overwrite)


if __name__ == "__main__":
    main()

