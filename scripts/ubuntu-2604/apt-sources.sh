#!/usr/bin/env bash
# Install the narrowly scoped repositories used by the Ubuntu desktop.
set -euo pipefail

root=${1:?target root directory required}
apt_dir=$root/etc/apt
work=$(mktemp -d)
trap 'rm -r -- "$work"' EXIT
install -d -m 0755 "$apt_dir/keyrings" "$apt_dir/sources.list.d" "$apt_dir/preferences.d"

dux_key=6354D7C7146C2DD8F98892895ABFA2A5F21E824E
mozilla_key=35BAA0B33E9EB396F59CA838C0BA5CE6DC6315A3
install_key() {
    local url=$1 fingerprint=$2 name=$3
    curl -fsSL --retry 3 "$url" -o "$work/$name.asc"
    gpg --show-keys --with-colons "$work/$name.asc" | grep -F "fpr:::::::::$fingerprint:"
    install -m 0644 "$work/$name.asc" "$apt_dir/keyrings/$name.asc"
}
install_key "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x$dux_key" "$dux_key" dux
install_key https://packages.mozilla.org/apt/repo-signing-key.gpg "$mozilla_key" mozilla
curl -fsSL --retry 3 https://pkg.noctalia.dev/deb/nickh-archive-keyring.deb -o "$work/noctalia.deb"
dpkg-deb -x "$work/noctalia.deb" "$work"
install -m 0644 "$work/usr/share/keyrings/nickh-archive-keyring.gpg" "$apt_dir/keyrings/noctalia.gpg"

cat > "$apt_dir/sources.list.d/cloudbook-dux.sources" <<'EOF'
Types: deb
URIs: https://ppa.launchpadcontent.net/duxlabs/dux-repo/ubuntu/
Suites: resolute
Components: main
Architectures: arm64
Signed-By: /etc/apt/keyrings/dux.asc
EOF
cat > "$apt_dir/sources.list.d/cloudbook-noctalia.sources" <<'EOF'
Types: deb
URIs: https://pkg.noctalia.dev/deb/
Suites: resolute/
Architectures: arm64
Signed-By: /etc/apt/keyrings/noctalia.gpg
EOF
cat > "$apt_dir/sources.list.d/cloudbook-mozilla.sources" <<'EOF'
Types: deb
URIs: https://packages.mozilla.org/apt
Suites: mozilla
Components: main
Architectures: arm64
Signed-By: /etc/apt/keyrings/mozilla.asc
EOF
cat > "$apt_dir/preferences.d/cloudbook-desktop" <<'EOF'
Package: niri xwayland-satellite
Pin: origin ppa.launchpadcontent.net
Pin-Priority: 500

Package: noctalia noctalia-data fonts-noctalia-tabler
Pin: origin pkg.noctalia.dev
Pin-Priority: 500

Package: firefox firefox-l10n-zh-cn
Pin: origin packages.mozilla.org
Pin-Priority: 1001

Package: *
Pin: origin ppa.launchpadcontent.net
Pin-Priority: -1

Package: *
Pin: origin pkg.noctalia.dev
Pin-Priority: -1

Package: *
Pin: origin packages.mozilla.org
Pin-Priority: -1
EOF
