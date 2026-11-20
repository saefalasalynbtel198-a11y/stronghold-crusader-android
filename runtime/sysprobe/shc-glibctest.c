/*
 * shc-glibctest — exercises the glibc code paths patched for Android's seccomp filter
 * (runtime/glibc-android/android-seccomp.patch): process start-up (TLS init, robust list,
 * rseq), thread creation (clone/clone3), fork, faccessat(AT_EACCESS) and
 * fchmodat(AT_SYMLINK_NOFOLLOW) and accept() on a Unix socket (as wineserver does). Prints
 * "glibctest ok" only if every step succeeded.
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
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

static void *worker(void *arg) { *(int *)arg = 42; return NULL; }

static int fail(const char *what) { printf("FAIL %s: %s\n", what, strerror(errno)); return 1; }

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

    printf("glibctest ok\n");
    return 0;
}
