#!/bin/bash
# Quick checks that the Linux environment works. Prints one line per check.
echo "kernel: $(uname -srm)"
echo "distro: $(. /etc/os-release && echo "$PRETTY_NAME")"
echo "user:   $(id -un) ($(id -u))"
echo "dns:    $(getent hosts deb.debian.org >/dev/null 2>&1 && echo ok || echo 'not yet (needs network)')"
echo "disk:   $(df -h / | awk 'NR==2{print $4" free"}')"
