#!/usr/bin/env bash
set -euo pipefail

root=${1:?target root directory required}
overlay=/work/rootfs_overlay
ubuntu_desktop=/work/scripts/ubuntu-2604
desktop=$ubuntu_desktop/desktop
home=$root/home/kuaner

printf 'cloudbook\n' > "$root/etc/hostname"
cat > "$root/etc/hosts" <<'EOF'
127.0.0.1 localhost
127.0.1.1 cloudbook
::1 localhost ip6-localhost ip6-loopback
EOF
chroot "$root" locale-gen zh_CN.UTF-8
chroot "$root" update-locale LANG=zh_CN.UTF-8 LANGUAGE=zh_CN:zh
ln -sfn /usr/share/zoneinfo/Asia/Shanghai "$root/etc/localtime"
printf 'Asia/Shanghai\n' > "$root/etc/timezone"
install -d "$root/etc/systemd/timesyncd.conf.d"
printf '[Time]\nNTP=ntp.aliyun.com time.cloudflare.com\nFallbackNTP=ntp.ubuntu.com\n' \
    > "$root/etc/systemd/timesyncd.conf.d/70-cloudbook.conf"
chroot "$root" useradd -m -s /bin/zsh -G sudo,video kuaner
printf 'kuaner:%s\n' "${CLOUDBOOK_LOGIN_PASSWORD:-kuaner}" | chroot "$root" chpasswd

for config in \
    etc/default/zramswap \
    etc/systemd/logind.conf.d/90-cloudbook-power.conf \
    etc/systemd/sleep.conf.d/90-cloudbook-sleep.conf \
    etc/systemd/system/cloudbook-mark-boot-successful.service \
    etc/udev/rules.d/90-backlight.rules \
    usr/share/polkit-1/actions/io.github.kuaner.cloudbook.switch-system.policy; do
    install -D -m 0644 "$overlay/$config" "$root/$config"
done
for executable in \
    etc/initramfs-tools/scripts/local-top/00-usb-first \
    usr/local/sbin/cloudbook-mark-boot-successful \
    usr/local/bin/cloudbook-switch-system; do
    install -D -m 0755 "$overlay/$executable" "$root/$executable"
done
for directory in usr/share/alsa/ucm2 usr/share/plymouth/themes/cloudbook-air; do
    install -d "$root/$directory"
    tar -C "$overlay/$directory" --owner=0 --group=0 -cf - . |
        tar -C "$root/$directory" --numeric-owner -xf -
done
install -D -m 0644 "$overlay/home/kuaner/Pictures/tokyo.png" "$root/usr/share/backgrounds/cloudbook/tokyo.png"
tar -C "$root/usr/share" --no-same-owner -xf "$overlay/usr/local/share/cloudbook/archcraft-desktop-assets.tar.zst"

for config in config.kdl foot.ini; do
    install -D -m 0644 "$desktop/$config" "$root/etc/niri/$config"
done
install -m 0644 "$desktop/noctalia.kdl" "$root/etc/niri/desktop.kdl"
install -D -m 0755 "$desktop/terminal" "$root/usr/local/bin/cloudbook-niri-terminal"
install -D -m 0644 "$desktop/noctalia.toml" "$home/.config/noctalia/config.toml"
install -D -m 0644 "$ubuntu_desktop/51-cloudbook-audio.conf" \
    "$root/etc/wireplumber/wireplumber.conf.d/51-cloudbook-audio.conf"
install -D -m 0644 "$ubuntu_desktop/niri-portals.conf" \
    "$root/etc/xdg/xdg-desktop-portal/niri-portals.conf"
install -D -m 0644 "$ubuntu_desktop/niri-mimeapps.list" \
    "$root/etc/xdg/niri-mimeapps.list"
install -d "$home/.config/fcitx5"
cat > "$home/.config/fcitx5/profile" <<'EOF'
[Groups/0]
Name=Default
Default Layout=us
DefaultIM=pinyin

[Groups/0/Items/0]
Name=keyboard-us
Layout=

[Groups/0/Items/1]
Name=pinyin
Layout=

[GroupOrder]
0=Default
EOF
install -d "$home/.config/autostart"
# Niri starts Fcitx itself. Mask the distribution autostart for this session.
for entry in im-launch org.fcitx.Fcitx5; do
    printf '[Desktop Entry]\nHidden=true\n' > "$home/.config/autostart/$entry.desktop"
done
install -D -m 0644 "$desktop/90-cloudbook.gschema.override" \
    "$root/usr/share/glib-2.0/schemas/90-cloudbook.gschema.override"
chroot "$root" glib-compile-schemas --strict /usr/share/glib-2.0/schemas
install -D -m 0644 "$desktop/70-cloudbook-noctalia-stop.conf" \
    "$root/etc/systemd/user/app-niri-noctalia-.scope.d/70-cloudbook-noctalia-stop.conf"
install -D -m 0644 "$desktop/greetd.toml" "$root/etc/greetd/cloudbook.toml"
install -D -m 0644 "$desktop/greetd.service.conf" \
    "$root/etc/systemd/system/greetd.service.d/noctalia-greeter.conf"
install -D -m 0644 "$desktop/plymouth-quit.service.conf" \
    "$root/etc/systemd/system/plymouth-quit.service.d/90-cloudbook.conf"
chroot "$root" install -d -m 0750 -o _greetd -g _greetd /var/lib/noctalia-greeter
install -m 0640 "$desktop/noctalia-greeter.toml" "$root/var/lib/noctalia-greeter/greeter.toml"
chroot "$root" chown root:_greetd /var/lib/noctalia-greeter/greeter.toml
for tty in tty1 tty2; do
    ln -sfn /dev/null "$root/etc/systemd/system/getty@$tty.service"
    ln -sfn /dev/null "$root/etc/systemd/system/autovt@$tty.service"
done

omz_revision=7140fedbfaab9315fe9dfd7e7b4d2e91fe316c31
install -d "$root/usr/local/share/oh-my-zsh"
curl -fsSL --retry 3 "https://codeload.github.com/ohmyzsh/ohmyzsh/tar.gz/$omz_revision" |
    tar -xz --strip-components=1 --no-same-owner -C "$root/usr/local/share/oh-my-zsh"
install -m 0644 "$desktop/zshrc" "$home/.zshrc"
mkdir -p "$home/Pictures"
chroot "$root" chown -R kuaner:kuaner /home/kuaner
printf '\nXMODIFIERS=@im=fcitx\nQT_IM_MODULE=fcitx\n' >> "$root/etc/environment"

for entry in foot-server footclient; do
    install -d "$root/usr/local/share/applications"
    printf '[Desktop Entry]\nHidden=true\n' > "$root/usr/local/share/applications/$entry.desktop"
done
cat > "$root/etc/initramfs-tools/conf.d/cloudbook" <<'EOF'
MODULES=list
COMPRESS=zstd
BUSYBOX=auto
FSTYPE=ext4
EOF
chroot "$root" update-alternatives --install /usr/share/plymouth/themes/default.plymouth \
    default.plymouth /usr/share/plymouth/themes/cloudbook-air/cloudbook-air.plymouth 200
chroot "$root" update-alternatives --set default.plymouth \
    /usr/share/plymouth/themes/cloudbook-air/cloudbook-air.plymouth
if [[ -e $root/usr/lib/firmware/regulatory.db-upstream ]]; then
    chroot "$root" update-alternatives --set regulatory.db /lib/firmware/regulatory.db-upstream
fi

cat > "$root/etc/systemd/system/cloudbook-ssh-host-keys.service" <<'EOF'
[Unit]
Description=Generate CloudBook SSH host keys
ConditionPathExists=!/etc/ssh/ssh_host_ed25519_key
Before=ssh.service

[Service]
Type=oneshot
ExecStart=/usr/bin/ssh-keygen -A

[Install]
RequiredBy=ssh.service
EOF
chroot "$root" systemctl disable ssh.socket NetworkManager-wait-online.service \
    ubuntu-advantage.service ua-reboot-cmds.service ua-timer.timer motd-news.timer
chroot "$root" systemctl enable NetworkManager.service bluetooth.service ssh.service \
    cloudbook-ssh-host-keys.service cloudbook-mark-boot-successful.service \
    systemd-timesyncd.service zramswap.service greetd.service
chroot "$root" systemctl set-default graphical.target
