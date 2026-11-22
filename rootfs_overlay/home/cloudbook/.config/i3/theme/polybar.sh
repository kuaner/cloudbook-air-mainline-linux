#!/usr/bin/env bash

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Launch the bar (system.ini is pre-configured for this device; skip the
# Archcraft auto-detection because it needs light/ip/acpi which we don't ship)
launch_bar() {
	killall -q polybar 2>/dev/null || true
	while pgrep -u $UID -x polybar >/dev/null; do sleep 1; done
	for mon in $(polybar --list-monitors | cut -d":" -f1); do
		MONITOR=$mon polybar -q main -c "$DIR"/polybar/config.ini &
	done
}

launch_bar
