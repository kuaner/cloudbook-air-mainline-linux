#!/usr/bin/env python3
"""Extract the W620D DTS stored as a new file in the Android board patch."""

import argparse
from pathlib import Path


DTS_PATH = "arch/arm64/boot/dts/rockchip/rk3568-ecloudbook-air-24rtw.dts"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("patch", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    lines = args.patch.read_text().splitlines()
    header = f"diff --git a/{DTS_PATH} b/{DTS_PATH}"
    start = lines.index(header)
    hunk = next(index for index in range(start, len(lines))
                if lines[index].startswith("@@ -0,0 +1,"))
    content = []
    for line in lines[hunk + 1:]:
        if line.startswith("diff --git "):
            break
        if line.startswith("+"):
            content.append(line[1:])
    if len(content) < 1000 or '#include "rk3568.dtsi"' not in content:
        raise RuntimeError("Board DTS in the patch is missing or unexpectedly short")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(content) + "\n")
    print(f"Extracted {len(content)} lines from {args.patch}")


if __name__ == "__main__":
    main()
