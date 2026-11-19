#!/usr/bin/env bash
# Runs a command in a private mount namespace where the host's x86-64 libraries
# (/usr/lib/x86_64-linux-gnu; /lib and /lib64 point into it on Ubuntu) are hidden by an empty tmpfs.
# Used by test-runtime-qemu.sh with the static qemu-aarch64 so Box64 can only use packaged libraries.
set -euo pipefail
exec sudo --preserve-env unshare --mount -- bash -c \
  'mount -t tmpfs -o size=1m tmpfs /usr/lib/x86_64-linux-gnu && exec "$@"' hermetic "$@"
