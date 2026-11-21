#!/usr/bin/env bash
# CI only: runs a command as the invoking (non-root) user in an Android-like environment, in place of this script.
#   sudo --preserve-env unshare --mount -- android-like-exec.sh <deny-link binary> <command...>
# /tmp, /var, /usr/lib/xorg and /usr/share/X11 are empty and read-only (nothing an app can rely on), and the
# command's hard links fail with EACCES (deny-link: seccomp filter, like Android's SELinux rule for apps).
# The working directory is kept. exec all the way down: the command gets this process's PID.
set -eu
deny="$1"; shift
for d in /tmp /var /usr/lib/xorg /usr/share/X11; do
  [ -d "$d" ] && mount -t tmpfs -o ro,size=4k,mode=0555 none "$d"
done
exec setpriv --reuid="$SUDO_UID" --regid="$SUDO_GID" --init-groups "$deny" "$@"
