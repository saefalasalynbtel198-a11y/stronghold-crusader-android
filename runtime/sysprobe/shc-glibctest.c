/*
 * shc-glibctest — exercises the glibc code paths patched for Android's seccomp filter
 * (runtime/glibc-android/android-seccomp.patch): process start-up (TLS init, robust list,
 * rseq), thread creation (clone/clone3), fork, faccessat(AT_EACCESS) and
 * fchmodat(AT_SYMLINK_NOFOLLOW), accept() on a Unix socket (as wineserver does), setuid/setgid
 * (own id: success without the syscall; any other request must still reach the kernel) and shmget
 * (ENOSYS without the syscall; mmap/memfd shared memory unaffected). Prints "glibctest ok" only if
 * every step succeeded.
 * Runs on the device as a self-test step and in CI under a simulated Android filter.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <gnu/libc-version.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <sys/ipc.h>
#include <sys/mman.h>
#include <sys/shm.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

static void *worker(void *arg) { *(int *)arg = 42; return NULL; }

static int fail(const char *what) { printf("FAIL %s: %s\n", what, strerror(errno)); return 1; }


/* Runs setuid(target) (is_gid: setgid) in a child and classifies what happened. The request is a real identity
 * change, so the kernel must decide it: EPERM (not allowed), success with the IDs really changed (allowed, e.g.
 * root), or SIGSYS (trapped by seccomp, i.e. the syscall was made). Success with unchanged IDs = bypass. */
static int check_change(int is_gid, unsigned target, int mixed, const char *label) {
    pid_t pid = fork();
    if (pid < 0) return fail("fork");
    if (pid == 0) {
        if (mixed) {  /* root only: saved ID different from real/effective */
            int r = is_gid ? setresgid(-1, -1, target + 1) : setresuid(-1, -1, target + 1);
            if (r != 0) _exit(20);
        }
        errno = 0;
        int r = is_gid ? setgid(target) : setuid(target);
        int err = errno;
        unsigned a, b, c;
        if (is_gid) { gid_t x, y, z; getresgid(&x, &y, &z); a = x; b = y; c = z; }
        else { uid_t x, y, z; getresuid(&x, &y, &z); a = x; b = y; c = z; }
        if (r == -1 && err == EPERM) _exit(10);
        if (r == 0 && a == target && b == target && c == target) _exit(11);
        if (r == 0) _exit(12);  /* reported success but nothing changed: bypass */
        _exit(13);
    }
    int st = 0;
    waitpid(pid, &st, 0);
    if (WIFSIGNALED(st) && WTERMSIG(st) == SIGSYS) {
        printf("%s: reached the kernel (trapped by seccomp)\n", label);
        return 0;
    }
    int code = WIFEXITED(st) ? WEXITSTATUS(st) : -1;
    if (code == 10) { printf("%s: reached the kernel (EPERM)\n", label); return 0; }
    if (code == 11) { printf("%s: reached the kernel (changed by the kernel)\n", label); return 0; }
    printf("FAIL %s: %s (child status %d)\n", label,
           code == 12 ? "success without any ID change (bypassed)" : "unexpected result", code);
    return 1;
}

int main(int argc, char **argv) {
    const char *dir = argc > 1 ? argv[1] : "/tmp";
    printf("glibc %s\n", gnu_get_libc_version());

    int value = 0;
    pthread_t t;
    if (pthread_create(&t, NULL, worker, &value) != 0) return fail("pthread_create");
    pthread_join(t, NULL);
    if (value != 42) return fail("thread result");
    printf("thread: ok\n");

    pthread_mutexattr_t ma;
    pthread_mutex_t m;
    pthread_mutexattr_init(&ma);
    pthread_mutexattr_setrobust(&ma, PTHREAD_MUTEX_ROBUST);
    if (pthread_mutex_init(&m, &ma) != 0 || pthread_mutex_lock(&m) != 0 || pthread_mutex_unlock(&m) != 0)
        return fail("robust mutex");
    printf("robust mutex: ok\n");

    pid_t pid = fork();
    if (pid < 0) return fail("fork");
    if (pid == 0) _exit(7);
    int st = 0;
    if (waitpid(pid, &st, 0) != pid || !WIFEXITED(st) || WEXITSTATUS(st) != 7) return fail("fork child status");
    printf("fork: ok\n");

    if (faccessat(AT_FDCWD, dir, R_OK, AT_EACCESS) != 0) return fail("faccessat(AT_EACCESS)");
    printf("faccessat: ok\n");

    char path[4096];
    snprintf(path, sizeof path, "%s/shc-glibctest.tmp", dir);
    int fd = open(path, O_CREAT | O_WRONLY | O_TRUNC, 0600);
    if (fd < 0) return fail("create temp file");
    close(fd);
    int r = fchmodat(AT_FDCWD, path, 0644, AT_SYMLINK_NOFOLLOW);
    int e = errno;
    unlink(path);
    /* EOPNOTSUPP is the documented result when /proc is unavailable to the O_PATH fallback. */
    if (r != 0 && e != EOPNOTSUPP) { errno = e; return fail("fchmodat(AT_SYMLINK_NOFOLLOW)"); }
    printf("fchmodat: ok\n");

    /* wineserver's accept() on its master socket; Android's filter only allows accept4 */
    struct sockaddr_un sa;
    memset(&sa, 0, sizeof sa);
    sa.sun_family = AF_UNIX;
    snprintf(sa.sun_path, sizeof sa.sun_path, "%s/shc-glibctest.sock", dir);
    if (strlen(sa.sun_path) >= sizeof sa.sun_path - 1) {  /* path too long: use the abstract namespace */
        memset(sa.sun_path, 0, sizeof sa.sun_path);
        snprintf(sa.sun_path + 1, sizeof sa.sun_path - 1, "shc-glibctest-%d", (int)getpid());
    }
    unlink(sa.sun_path);
    int ls = socket(AF_UNIX, SOCK_STREAM, 0), cs = socket(AF_UNIX, SOCK_STREAM, 0);
    if (ls < 0 || cs < 0) return fail("socket");
    if (bind(ls, (struct sockaddr *)&sa, sizeof sa) != 0 || listen(ls, 1) != 0) return fail("bind/listen");
    if (connect(cs, (struct sockaddr *)&sa, sizeof sa) != 0) return fail("connect");
    int as = accept(ls, NULL, NULL);
    if (as < 0) return fail("accept");
    close(as); close(cs); close(ls);
    if (sa.sun_path[0]) unlink(sa.sun_path);
    printf("accept: ok\n");

    /* setuid/setgid to the current IDs: success without the syscall (Android traps it) */
    if (setuid(getuid()) != 0) return fail("setuid(own uid)");
    if (setgid(getgid()) != 0) return fail("setgid(own gid)");
    printf("setuid/setgid to own id: ok\n");
    /* real identity changes must still reach the kernel */
    unsigned other_uid = getuid() == 54321 ? 54322 : 54321, other_gid = getgid() == 54321 ? 54322 : 54321;
    if (check_change(0, other_uid, 0, "setuid(other uid)")) return 1;
    if (check_change(1, other_gid, 0, "setgid(other gid)")) return 1;
    if (geteuid() == 0) {
        if (check_change(0, getuid(), 1, "setuid(own uid, saved uid different)")) return 1;
        if (check_change(1, getgid(), 1, "setgid(own gid, saved gid different)")) return 1;
    } else {
        printf("setuid/setgid with mixed IDs: skipped (needs root to create them)\n");
    }

    /* shmget fails with ENOSYS without the syscall (Android traps it); other shared memory still works */
    errno = 0;
    int shm = shmget(IPC_PRIVATE, 4096, IPC_CREAT | 0600);
    if (shm != -1 || errno != ENOSYS) {
        printf("FAIL shmget: returned %d, errno %d (expected -1, ENOSYS)\n", shm, errno);
        if (shm >= 0) shmctl(shm, IPC_RMID, NULL);
        return 1;
    }
    printf("shmget: ENOSYS\n");
    volatile int *shared = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (shared == MAP_FAILED) return fail("mmap(MAP_SHARED)");
    *shared = 0;
    pid_t sp = fork();
    if (sp < 0) return fail("fork");
    if (sp == 0) { *shared = 4242; _exit(0); }
    waitpid(sp, NULL, 0);
    if (*shared != 4242) { printf("FAIL shared mmap: child write not visible\n"); return 1; }
    int mfd = memfd_create("shc-glibctest", MFD_CLOEXEC);
    if (mfd < 0 || ftruncate(mfd, 4096) != 0) return fail("memfd_create/ftruncate");
    char *m1 = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, mfd, 0);
    char *m2 = mmap(NULL, 4096, PROT_READ, MAP_SHARED, mfd, 0);
    if (m1 == MAP_FAILED || m2 == MAP_FAILED) return fail("mmap(memfd)");
    strcpy(m1, "memfd");
    if (strcmp(m2, "memfd") != 0) { printf("FAIL memfd: mappings differ\n"); return 1; }
    printf("shared mmap and memfd: ok\n");

    printf("glibctest ok\n");
    return 0;
}
