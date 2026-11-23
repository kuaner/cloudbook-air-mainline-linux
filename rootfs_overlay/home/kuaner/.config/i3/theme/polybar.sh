#!/bin/sh
set -eu

runtime="${XDG_RUNTIME_DIR:-$HOME/.cache}/cloudbook-i3"
mkdir -p "$runtime"
exec 9>"$runtime/polybar.lock"
flock -w 3 9

pid_file="$runtime/polybar.pid"
if [ -s "$pid_file" ]; then
    read -r old_pid < "$pid_file"
    if [ "$(ps -p "$old_pid" -o comm=)" = polybar ]; then
        kill "$old_pid" 2>/dev/null || :
        for attempt in 1 2 3 4 5 6 7 8 9 10; do
            [ "$(ps -p "$old_pid" -o state= | cut -c1)" != Z ] || break
            kill -0 "$old_pid" 2>/dev/null || break
            sleep 0.1
        done
    fi
fi

MONITOR=$(xrandr --listactivemonitors | awk '$2 ~ /\*/ {print $NF; exit}')
export MONITOR
polybar main -c "$HOME/.config/i3/theme/polybar/config.ini" \
    >"$runtime/polybar.log" 2>&1 </dev/null 9>&- &
printf '%s\n' "$!" > "$pid_file"
