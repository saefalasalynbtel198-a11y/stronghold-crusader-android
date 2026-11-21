/*
 * Preload library for the display feasibility test only (not part of the app or the runtime package).
 * It stands in for an Android fix still to be decided for runtime r6; the test's seccomp check shows that
 * nothing else on the display path needs a blocked syscall.
 *
 * 1. setuid/setgid to the caller's own ids: Xvfb's Popen() child calls setgid(getgid()) and setuid(getuid())
 *    before running xkbcomp (xserver os/utils.c). Android's app seccomp filter traps setuid/setgid even then
 *    (device probe: "control: setuid(own uid) BLOCKED"). For a non-root process this case changes nothing, so
 *    it returns success without the syscall; anything else goes to the kernel unchanged.
 * 2. shmget: Wine's X11 driver tries a System V shared-memory image for every window surface without asking
 *    whether the X server has MIT-SHM (winex11.drv/bitblt.c create_shm_image) and falls back to plain XImages
 *    when that fails. Android traps shmget, which would kill the process at its first window. Returning -1 with
 *    ENOSYS (as on a kernel without SysV IPC) takes Wine's own fallback path.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <sys/ipc.h>
#include <sys/shm.h>
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

int shmget(key_t key, size_t size, int flags) {
  (void)key; (void)size; (void)flags;
  errno = ENOSYS;
  return -1;
}
