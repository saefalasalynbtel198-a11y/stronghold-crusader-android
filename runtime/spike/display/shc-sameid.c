/*
 * Preload library for Xvfb (display feasibility test only).
 *
 * Xvfb's Popen() child calls setgid(getgid()) and setuid(getuid()) before running xkbcomp through /bin/sh
 * (xserver os/utils.c). Android's app seccomp filter traps setuid/setgid even when they change nothing
 * (device probe: "control: setuid(own uid) BLOCKED"), so the child would be killed and Xvfb would fail to
 * build its keymap. For a non-root process, setuid(x)/setgid(x) with x already the real and effective id is a
 * no-op; this returns success for exactly that case without making the syscall. Any other request goes to the
 * kernel unchanged (and is still trapped on Android, as it should be).
 */
#define _GNU_SOURCE
#include <sys/syscall.h>
#include <unistd.h>

int setuid(uid_t uid) {
  if (uid == getuid() && uid == geteuid()) return 0;
  return (int)syscall(SYS_setuid, uid);
}

int setgid(gid_t gid) {
  if (gid == getgid() && gid == getegid()) return 0;
  return (int)syscall(SYS_setgid, gid);
}
