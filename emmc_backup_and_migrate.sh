#!/usr/bin/env bash
# ==============================================================================
# ZTE CloudBook Air (CT2000-24RTW / RK3568B2)
# Automated /dev/mmcblk0p20 (userdata) Backup + eMMC Rootfs Migration + v29 Flash
# ==============================================================================
set -euo pipefail

HOST_IP="${HOST_IP:-192.0.2.1}"
HTTP_PORT="${HTTP_PORT:-18080}"
BACKUP_PORT="${BACKUP_PORT:-18090}"
LOG_PORT="${LOG_PORT:-18091}"
EXPECTED_V29_SHA256="4c8a07fecb7ab48cbc80faebac25aa9113de173922dee8989f3d3ce78e0840a2"
EXPECTED_V29_SIZE=41187328

log_msg() {
    local msg="[$(date '+%H:%M:%S')] $*"
    echo "$msg"
    if [[ -w /dev/ttyS2 ]]; then
        echo "$msg" > /dev/ttyS2 2>/dev/null || true
    fi
    python3 - "$HOST_IP" "$LOG_PORT" "$msg" <<'PYLOG' 2>/dev/null || true
import socket, sys
try:
    with socket.create_connection((sys.argv[1], int(sys.argv[2])), timeout=2) as s:
        s.sendall((sys.argv[3] + "\n").encode())
except Exception:
    pass
PYLOG
}

# 0. Restore ttyS2 115200 8N1 echo and clean up any stuck python3 reader
stty -F /dev/ttyS2 115200 cs8 -parenb -cstopb clocal -crtscts -ixon -ixoff echo echoe echok 2>/dev/null || true
pkill -9 -f 'os.open\("/dev/mmcblk0p20"' 2>/dev/null || true

log_msg "=== STEP 1/5: Pre-flight checks on board ==="
ROOT_DEV="$(findmnt -n -o SOURCE /)"
log_msg "Current rootfs device: $ROOT_DEV"
if [[ "$ROOT_DEV" == "/dev/mmcblk0p20" ]]; then
    log_msg "ERROR: Already booted from /dev/mmcblk0p20! Aborting."
    exit 1
fi
umount /dev/mmcblk0p20 2>/dev/null || true

P20_SIZE="$(blockdev --getsize64 /dev/mmcblk0p20)"
log_msg "/dev/mmcblk0p20 size: $P20_SIZE bytes"
if [[ "$P20_SIZE" -ne 23627530240 ]]; then
    log_msg "ERROR: Unexpected /dev/mmcblk0p20 size ($P20_SIZE != 23627530240)"
    exit 1
fi

# 1. Stream full /dev/mmcblk0p20 backup to host before formatting
log_msg "=== STEP 2/5: Backing up /dev/mmcblk0p20 (userdata, 22 GiB) to host ($HOST_IP:$BACKUP_PORT) ==="
python3 - "$HOST_IP" "$BACKUP_PORT" <<'PYBACKUP'
import os, socket, struct, sys, time

host = sys.argv[1]
port = int(sys.argv[2])
expected = 23627530240
chunk_size = 1024 * 1024  # 1 MiB
zbuf = b"\x00" * chunk_size

fd = os.open("/dev/mmcblk0p20", os.O_RDONLY)
sock = socket.create_connection((host, port), timeout=30)
t0 = time.time()
sent = 0
last_report = t0

try:
    while True:
        buf = os.read(fd, chunk_size)
        if not buf:
            break
        n = len(buf)
        if n == chunk_size and buf == zbuf:
            sock.sendall(b"ZERO" + struct.pack("<I", n))
        elif buf == b"\x00" * n:
            sock.sendall(b"ZERO" + struct.pack("<I", n))
        else:
            sock.sendall(b"DATA" + struct.pack("<I", n) + buf)
        sent += n
        now = time.time()
        if now - last_report >= 10.0 or sent == expected:
            pct = sent * 100.0 / expected
            rate = (sent / (1024 * 1024)) / max(now - t0, 0.001)
            print(f"  Backup progress: {sent/(1024**3):.2f}/{expected/(1024**3):.2f} GiB ({pct:.1f}%) @ {rate:.1f} MiB/s", flush=True)
            last_report = now

    if sent != expected:
        raise RuntimeError(f"Short read from /dev/mmcblk0p20: {sent} != {expected}")

    sock.sendall(b"DONE\x00\x00\x00\x00")
    print("  Waiting for host zstd flush & SHA256 verification...", flush=True)
    sock.settimeout(120)
    resp = sock.recv(1024).decode(errors="replace").strip()
    print(f"  Host response: {resp}", flush=True)
    if not resp.startswith("BACKUP_VERIFIED_OK"):
        raise RuntimeError(f"Host backup verification failed: {resp}")
finally:
    os.close(fd)
    sock.close()
PYBACKUP
log_msg "=== STEP 2/5 COMPLETE: /dev/mmcblk0p20 backed up and verified on host ==="

# 2. Install one-click factory Android restore tools onto USB rescue rootfs (/root/factory_restore)
log_msg "=== STEP 3/5: Installing one-click Factory Android restore script onto USB rescue drive ==="
mkdir -p /root/factory_restore
curl -fsSL "http://${HOST_IP}:${HTTP_PORT}/factory_backup/restore_factory_android.sh" -o /root/factory_restore/restore_factory_android.sh
curl -fsSL "http://${HOST_IP}:${HTTP_PORT}/factory_backup/misc_android_slot_b.img" -o /root/factory_restore/misc_android_slot_b.img
chmod +x /root/factory_restore/restore_factory_android.sh
log_msg "Installed /root/factory_restore/restore_factory_android.sh and misc_android_slot_b.img on USB rescue stick"

# 3. Format /dev/mmcblk0p20 as ext4 (LABEL=EMMC_ROOTFS) and clone rootfs
log_msg "=== STEP 4/5: Formatting /dev/mmcblk0p20 (LABEL=EMMC_ROOTFS) and cloning rootfs ==="
mkfs.ext4 -F -L EMMC_ROOTFS /dev/mmcblk0p20
mkdir -p /mnt/emmc
mount -t ext4 /dev/mmcblk0p20 /mnt/emmc

log_msg "Running rsync from / to /mnt/emmc ..."
rsync -axHAWX --numeric-ids \
    --exclude='/dev/*' \
    --exclude='/proc/*' \
    --exclude='/sys/*' \
    --exclude='/tmp/*' \
    --exclude='/run/*' \
    --exclude='/mnt/*' \
    --exclude='/media/*' \
    --exclude='/lost+found' \
    / /mnt/emmc/

mkdir -p /mnt/emmc/{dev,proc,sys,tmp,run,mnt,media}
chmod 1777 /mnt/emmc/tmp

# Configure /mnt/emmc/etc/fstab for LABEL=EMMC_ROOTFS
cat > /mnt/emmc/etc/fstab <<'FSTAB'
LABEL=EMMC_ROOTFS / ext4 defaults,noatime,commit=120,errors=remount-ro 0 1
tmpfs /tmp tmpfs defaults,nosuid 0 0
FSTAB

# Configure systemd-logind & sleep.conf: remove Armbian's 00-disable.conf (AllowSuspend=no)
# and enable Hall lid switch deep suspend
mkdir -p /mnt/emmc/etc/systemd/logind.conf.d /mnt/emmc/etc/systemd/sleep.conf.d
rm -f /mnt/emmc/etc/systemd/logind.conf.d/90-bringup-no-lid-suspend.conf \
      /mnt/emmc/etc/systemd/sleep.conf.d/00-disable.conf
cat > /mnt/emmc/etc/systemd/sleep.conf.d/90-cloudbook-sleep.conf <<'SLEEP'
[Sleep]
AllowSuspend=yes
SuspendState=mem
SLEEP
cat > /mnt/emmc/etc/systemd/logind.conf.d/90-cloudbook-power.conf <<'LOGIND'
[Login]
HandleLidSwitch=suspend
HandleLidSwitchExternalPower=suspend
HandleLidSwitchDocked=suspend
LidSwitchIgnoreInhibited=yes
HandlePowerKey=ignore
LOGIND

# Ensure serial-getty@ttyS2 override has clean 0644 permissions and 115200 baud
if [[ -d /mnt/emmc/etc/systemd/system/serial-getty@ttyS2.service.d ]]; then
    chmod 0644 /mnt/emmc/etc/systemd/system/serial-getty@ttyS2.service.d/*.conf 2>/dev/null || true
fi

# Verify critical paths on /mnt/emmc
if [[ ! -L /mnt/emmc/lib || ! -d /mnt/emmc/lib/modules/6.12.117-cloudbook || ! -x /mnt/emmc/sbin/init ]]; then
    log_msg "ERROR: Rootfs verification failed on /mnt/emmc!"
    exit 1
fi

EMMC_DF="$(df -h /mnt/emmc | tail -n 1)"
log_msg "eMMC Rootfs clone verified OK ($EMMC_DF)"
sync
umount /mnt/emmc
sync

# 4. Flash boot_linux_612_v29_emmc.img to boot_a (/dev/mmcblk0p11) and verify SHA256 + misc
log_msg "=== STEP 5/5: Flashing boot_linux_612_v29_emmc.img to /dev/mmcblk0p11 (boot_a) ==="
curl -fsSL "http://${HOST_IP}:${HTTP_PORT}/boot_linux_612_v29_emmc.img" -o /tmp/boot_linux_612_v29_emmc.img
DL_SHA="$(sha256sum /tmp/boot_linux_612_v29_emmc.img | awk '{print $1}')"
if [[ "$DL_SHA" != "$EXPECTED_V29_SHA256" ]]; then
    log_msg "ERROR: Downloaded v29 SHA256 mismatch ($DL_SHA != $EXPECTED_V29_SHA256)"
    exit 1
fi

dd if=/tmp/boot_linux_612_v29_emmc.img of=/dev/mmcblk0p11 bs=4M conv=fsync status=none
DISK_SHA="$(head -c "$EXPECTED_V29_SIZE" /dev/mmcblk0p11 | sha256sum | awk '{print $1}')"
if [[ "$DISK_SHA" != "$EXPECTED_V29_SHA256" ]]; then
    log_msg "ERROR: On-disk /dev/mmcblk0p11 SHA256 mismatch ($DISK_SHA != $EXPECTED_V29_SHA256)"
    exit 1
fi
rm -f /tmp/boot_linux_612_v29_emmc.img
sync

log_msg "Verified /dev/mmcblk0p11 (boot_a) SHA256 = $DISK_SHA"
log_msg "=== ALL 5 STEPS SUCCEEDED! READY TO UNPLUG USB DRIVE AND REBOOT INTO eMMC! ==="
