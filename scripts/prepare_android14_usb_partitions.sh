#!/bin/sh
# Run in the Android 14 Linux build container; only works on copied images.
set -eu
INPUT=${1:?source partition directory}
OUTPUT=${2:?new partition directory}
FIRMWARE=${3:-/work/artifacts/firmware/7.3.5-cloudbook}
mkdir -p "$OUTPUT"
if find "$OUTPUT" -mindepth 1 -print -quit | grep -q .; then
    echo "Refusing to reuse a non-empty output directory: $OUTPUT" >&2
    exit 1
fi
cp "$INPUT"/*.img "$OUTPUT/"
cp /work/android14/usb/partitions.tsv "$OUTPUT/partitions.tsv"
resize2fs "$OUTPUT/vendor_a.img" 384M
VENDOR="$OUTPUT/vendor_a.img"
TEMP=$(mktemp -d)
trap 'rm -rf "$TEMP"' EXIT INT TERM

replace_file() {
    source=$1
    path=$2
    group=$3
    debugfs -w -R "rm $path" "$VENDOR" >/dev/null 2>&1 || true
    debugfs -w -R "write $source $path" "$VENDOR"
    debugfs -w -R "set_inode_field $path mode 0100644" "$VENDOR"
    debugfs -w -R "set_inode_field $path uid 0" "$VENDOR"
    debugfs -w -R "set_inode_field $path gid $group" "$VENDOR"
    debugfs -w -R "ea_set $path security.selinux u:object_r:vendor_configs_file:s0" "$VENDOR"
    debugfs -R "dump $path $TEMP/readback" "$VENDOR" >/dev/null 2>&1
    cmp "$source" "$TEMP/readback"
    rm "$TEMP/readback"
}

replace_file /work/android14/usb/fstab.rk30board /etc/fstab.rk30board 0
replace_file /work/android14/usb/init.cloudbook-usb.rc /etc/init/init.cloudbook-usb.rc 0
replace_file "$FIRMWARE/WIFI_RAM_CODE_MT7663.bin" /etc/firmware/mediatek/mt7663_n9_v3.bin 2000
replace_file "$FIRMWARE/WIFI_RAM_CODE_MT7663.bin" /etc/firmware/mediatek/mt7663_n9_rebb.bin 2000
replace_file "$FIRMWARE/mt7663_patch_e2_hdr.bin" /etc/firmware/mediatek/mt7663pr2h.bin 2000
replace_file "$FIRMWARE/mt7663_patch_e2_hdr.bin" /etc/firmware/mediatek/mt7663pr2h_rebb.bin 2000
e2fsck -fy "$OUTPUT/vendor_a.img"
for image in "$OUTPUT"/*.img; do
    echo "== $(basename "$image") =="
    debugfs -R stats "$image" 2>/dev/null | grep 'Filesystem state'
done
