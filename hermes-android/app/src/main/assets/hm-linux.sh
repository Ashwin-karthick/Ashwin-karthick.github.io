# Hermes Mobile: start a command (or a login shell) inside the app's Ubuntu userland via proot.
# Run with /system/bin/sh on Android. POSIX sh only (Android's shell is mksh).
#
# Usage: sh hm-linux.sh [COMMAND-STRING]      no argument = interactive login shell
#
# Environment (set by the app):
#   HM_PROOT            path to the proot binary
#   HM_ROOTFS           path to the Ubuntu root filesystem
#   HM_SYSDATA          directory holding stand-in /proc files
#   HM_TERMUX_PROOT     1 = Termux's proot build (enables its Android-only options)
#   HM_EXTRA_PROOT_ARGS extra proot arguments (tests only, e.g. "-q qemu-aarch64-static")
#   HM_GUEST_ENV        extra NAME=value words passed into Ubuntu (tests only)
#   HM_SECRET           optional secret handed to the command as $HM_SECRET, never on a command line
set -u

if [ -n "${1:-}" ]; then
  HM_CMD="$1"
  set -- /bin/bash -lc "$HM_CMD"
else
  set -- /bin/bash -l
fi

# A clean environment inside Ubuntu; nothing from Android leaks in except what is listed here.
# UV_LINK_MODE=copy: uv hard-links by default, which fails under proot's --link2symlink
# ("failed to hardlink ... .l2s.*: Invalid argument"), so Hermes' Python install needs copies.
# shellcheck disable=SC2086
set -- /usr/bin/env -i \
  HOME=/root USER=root LOGNAME=root SHELL=/bin/bash \
  TERM="${TERM:-xterm-256color}" COLORTERM=truecolor LANG=C.UTF-8 LC_ALL=C.UTF-8 TMPDIR=/tmp \
  PATH=/root/.local/bin:/root/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  HM_SECRET="${HM_SECRET:-}" HERMES_DASHBOARD_SESSION_TOKEN="${HERMES_DASHBOARD_SESSION_TOKEN:-}" \
  UV_LINK_MODE=copy \
  ${HM_GUEST_ENV:-} \
  "$@"

# Android blocks some /proc files for apps; stand-ins are bound only where the real one is unreadable.
for name in loadavg stat uptime version vmstat; do
  if ! cat "/proc/$name" >/dev/null 2>&1 && [ -f "$HM_SYSDATA/$name" ]; then
    set -- --bind="$HM_SYSDATA/$name:/proc/$name" "$@"
  fi
done
if [ -d "$HM_SYSDATA/sys_empty" ]; then
  set -- --bind="$HM_SYSDATA/sys_empty:/sys/fs/selinux" "$@"
fi

set -- \
  --rootfs="$HM_ROOTFS" --root-id --cwd=/root \
  --bind=/dev --bind=/proc --bind=/sys \
  --bind=/dev/urandom:/dev/random \
  --bind=/proc/self/fd:/dev/fd \
  --bind=/proc/self/fd/0:/dev/stdin --bind=/proc/self/fd/1:/dev/stdout --bind=/proc/self/fd/2:/dev/stderr \
  --bind="$HM_ROOTFS/tmp:/dev/shm" \
  "$@"

if [ "${HM_TERMUX_PROOT:-0}" = 1 ]; then
  set -- --kill-on-exit --link2symlink --sysvipc -L \
    '--kernel-release=\Linux\localhost\6.17.0-PRoot-Distro\#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000\aarch64\localdomain\-1\' \
    "$@"
fi

# shellcheck disable=SC2086
exec "$HM_PROOT" ${HM_EXTRA_PROOT_ARGS:-} "$@"
