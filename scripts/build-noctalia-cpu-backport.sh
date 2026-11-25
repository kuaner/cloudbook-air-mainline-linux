#!/usr/bin/env bash
# Run inside a disposable Ubuntu 26.04 ARM64 container.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

if [[ $# != 1 || $(dpkg --print-architecture) != arm64 ]]; then
    echo "Usage (ARM64 builder): $0 OUTPUT_DIRECTORY" >&2
    exit 2
fi
output=$(realpath -- "$1")
patch_file=$(realpath -- "$(dirname -- "$0")/patches/noctalia-arm-cpu-model.patch")
. /etc/os-release
suite=resolute
if [[ $VERSION_CODENAME != "$suite" ]]; then
    echo "Unsupported Noctalia build suite: $VERSION_CODENAME" >&2
    exit 2
fi
version=5.6.0-1ubuntu1
revision=$version+cloudbook1
repo=https://pkg.noctalia.dev/deb/
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
chmod 0755 "$work"

apt-get update
apt-get install -y --no-install-recommends ca-certificates curl dpkg-dev build-essential
curl -fsSL --retry 3 "${repo}nickh-archive-keyring.deb" -o "$work/keyring.deb"
dpkg-deb -x "$work/keyring.deb" "$work/keyring"
install -D -m 0644 "$work/keyring/usr/share/keyrings/nickh-archive-keyring.gpg" \
    /etc/apt/keyrings/cloudbook-noctalia.gpg
cat > /etc/apt/sources.list.d/cloudbook-noctalia.sources <<EOF
Types: deb-src
URIs: $repo
Suites: $suite/
Signed-By: /etc/apt/keyrings/cloudbook-noctalia.gpg
EOF
    cat > /etc/apt/sources.list.d/noctalia-build-protocols.sources <<'EOF'
Types: deb
URIs: https://pkg.noctalia.dev/deb/
Suites: resolute-backports/
Signed-By: /etc/apt/keyrings/cloudbook-noctalia.gpg
EOF
cat > /etc/apt/preferences.d/noctalia-build-protocols <<'EOF'
Package: wayland-protocols
Pin: release n=resolute-backports
Pin-Priority: 990

Package: *
Pin: release n=resolute-backports
Pin-Priority: -1
EOF
apt-get update
apt-get install -y --no-install-recommends wayland-protocols
apt-get build-dep -y --no-install-recommends "noctalia=$version"

install -d -o _apt "$work/source"
cd "$work/source"
apt-get source --only-source "noctalia=$version"
cd noctalia-5.6.0
patch --batch --forward -p1 < "$patch_file"
{
    printf 'noctalia (%s) %s; urgency=medium\n\n' "$revision" "$suite"
    printf '  * Fall back to lscpu for CPU model names on ARM.\n\n'
    printf ' -- CloudBook Air maintainers <cloudbook@localhost>  %s\n\n' "$(date -R)"
    cat debian/changelog
} > debian/changelog.cloudbook
mv debian/changelog.cloudbook debian/changelog
DEB_BUILD_OPTIONS="parallel=${JOBS:-8} noddebs" dpkg-buildpackage -b -us -uc
install -m 0644 "../noctalia_${revision}_arm64.deb" \
    "../noctalia-data_${revision}_all.deb" "../fonts-noctalia-tabler_${revision}_all.deb" "$output/"
