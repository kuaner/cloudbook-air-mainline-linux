#!/usr/bin/env bash
# Build the CloudBook Air Ubuntu 26.04 LTS desktop for USB and eMMC.
set -euo pipefail

if [[ $# != 1 ]]; then
    echo "Usage: $0 OUTPUT_DIRECTORY" >&2
    exit 2
fi
repo=$(cd -- "$(dirname -- "$0")/.." && pwd)
mkdir -p "$1"
output=$(cd -- "$1" && pwd)
kernel=$(cd -- "${CLOUDBOOK_KERNEL_DIR:-$repo/artifacts/7.3}" && pwd)
packages=()
if [[ -n ${CLOUDBOOK_NOCTALIA_PACKAGES:-} ]]; then
    directory=$(cd -- "$CLOUDBOOK_NOCTALIA_PACKAGES" && pwd)
    packages=(--mount "type=bind,src=$directory,dst=/noctalia-packages,readonly")
fi
docker run --rm -i --privileged --platform linux/arm64 \
    --env CLOUDBOOK_LOGIN_PASSWORD \
    --mount "type=bind,src=$repo,dst=/work,readonly" \
    --mount "type=bind,src=$kernel,dst=/kernel,readonly" \
    --mount "type=bind,src=$output,dst=/out" \
    ${packages[@]+"${packages[@]}"} \
    ubuntu:26.04 bash /work/scripts/ubuntu-2604/build-rootfs.sh
