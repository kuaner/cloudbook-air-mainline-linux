#!/system/bin/sh
LOG=/dev/kmsg
printf '\nCLOUDBOOK-USB: first-stage USB filesystems\n' >$LOG
{
    date
    uname -a
    cat /proc/cmdline
    cat /proc/mounts
    ls -l /dev/block/platform/usbhost/by-name
    getprop ro.build.version.release
    getprop ro.build.version.sdk
    getprop ro.hardware.egl
    getprop ro.boot.slot_suffix
    ls -l /dev/dri
    dmesg | grep -iE 'usb|sda|edp|panel|drm|mali|mt76|btmtk|sbs|sc2021|ext4'
} >>$LOG 2>&1
printf 'CLOUDBOOK-USB: diagnostic complete\n' >$LOG

