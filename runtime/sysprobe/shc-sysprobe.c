/*
 * shc-sysprobe — freestanding syscall probe for Android's app seccomp filter.
 *
 * Built static with -nostdlib: it does not use glibc, so it starts even when glibc's own
 * start-up is killed by seccomp. For each probed syscall it reports ALLOWED (with the raw
 * return value) or BLOCKED (seccomp delivered SIGSYS). A SIGSYS handler records the syscall
 * number from siginfo, writes a sentinel into the return register and lets execution
 * continue after the trapped instruction, so one run maps every syscall in the list.
 *
 * Arguments are chosen to be harmless (invalid pointers/fds, query-only commands).
 *
 * Modes:
 *   shc-sysprobe                 probe the syscall list
 *   shc-sysprobe --trap-selftest install a filter that traps getppid and verify the handler sees it
 *                                (proves the detection mechanism works on this kernel)
 *
 * Supported architectures: aarch64 (target), x86_64 (developer self-test only).
 */
#define _GNU_SOURCE 1
#include <stddef.h>
#include <signal.h>
#include <ucontext.h>
#include <sys/syscall.h>
#include <linux/filter.h>
#include <linux/seccomp.h>

#define SENTINEL (-7777L)

/* ---------- raw syscalls ---------- */
#if defined(__aarch64__)
static long sc6(long n, long a, long b, long c, long d, long e, long f) {
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x5 __asm__("x5") = f;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5) : "memory", "cc");
    return x0;
}
__asm__(".text\n.global _start\n_start:\n mov x0, sp\n bl cstart\n mov x8, #93\n svc #0\n");
#define ARCH_NAME "aarch64"
#elif defined(__x86_64__)
static long sc6(long n, long a, long b, long c, long d, long e, long f) {
    long ret;
    register long r10 __asm__("r10") = d;
    register long r8 __asm__("r8") = e;
    register long r9 __asm__("r9") = f;
    __asm__ volatile("syscall" : "=a"(ret) : "a"(n), "D"(a), "S"(b), "d"(c), "r"(r10), "r"(r8), "r"(r9)
                     : "rcx", "r11", "memory");
    return ret;
}
__asm__(".text\n.global _start\n_start:\n xor %rbp,%rbp\n mov %rsp,%rdi\n and $-16,%rsp\n call cstart\n hlt\n");
__asm__(".text\n.global shc_restore_rt\nshc_restore_rt:\n mov $15,%rax\n syscall\n hlt\n");
void shc_restore_rt(void);
#define ARCH_NAME "x86_64"
#else
#error "unsupported architecture"
#endif

#define SC(n, a, b, c, d, e, f) sc6((long)(n), (long)(a), (long)(b), (long)(c), (long)(d), (long)(e), (long)(f))

/* Compiler may emit calls to these even with -ffreestanding. */
void *memset(void *d, int c, size_t n) { unsigned char *p = d; while (n--) *p++ = (unsigned char)c; return d; }
void *memcpy(void *d, const void *s, size_t n) { unsigned char *a = d; const unsigned char *b = s; while (n--) *a++ = *b++; return d; }

/* ---------- output ---------- */
static size_t slen(const char *s) { size_t n = 0; while (s[n]) n++; return n; }
static void out(const char *s) { SC(SYS_write, 1, s, slen(s), 0, 0, 0); }
static void outl(long v) {
    char buf[24]; int i = 23; int neg = v < 0; unsigned long u = neg ? (unsigned long)(-v) : (unsigned long)v;
    buf[i] = 0;
    do { buf[--i] = (char)('0' + u % 10); u /= 10; } while (u);
    if (neg) buf[--i] = '-';
    out(buf + i);
}

/* ---------- SIGSYS handling ---------- */
static volatile int g_trapped;
static volatile long g_trapped_nr;

static void on_sigsys(int sig, siginfo_t *si, void *ucv) {
    (void)sig;
    ucontext_t *uc = (ucontext_t *)ucv;
    g_trapped = 1;
    g_trapped_nr = si->si_syscall;
#if defined(__aarch64__)
    uc->uc_mcontext.regs[0] = (unsigned long)SENTINEL;
#else
    uc->uc_mcontext.gregs[REG_RAX] = SENTINEL;
#endif
}

struct ksigaction {
    void (*handler)(int, siginfo_t *, void *);
    unsigned long flags;
    void (*restorer)(void);
    unsigned long mask;
};

static int install_handler(void) {
    struct ksigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.handler = on_sigsys;
    sa.flags = SA_SIGINFO | SA_NODEFER;
#if defined(__x86_64__)
    sa.flags |= 0x04000000; /* SA_RESTORER */
    sa.restorer = shc_restore_rt;
#endif
    return (int)SC(SYS_rt_sigaction, SIGSYS, &sa, 0, 8, 0, 0);
}

/* ---------- probing ---------- */
static int g_blocked;
static char g_blocked_list[1024];
static size_t g_blocked_len;

static void note_blocked(const char *name) {
    size_t n = slen(name);
    if (g_blocked_len + n + 2 < sizeof g_blocked_list) {
        if (g_blocked_len) g_blocked_list[g_blocked_len++] = ',';
        memcpy(g_blocked_list + g_blocked_len, name, n);
        g_blocked_len += n;
        g_blocked_list[g_blocked_len] = 0;
    }
}

static void report(const char *name, long nr, long ret) {
    out(name); out(" ("); outl(nr); out("): ");
    if (g_trapped) {
        out("BLOCKED by seccomp (SIGSYS, si_syscall="); outl(g_trapped_nr); out(")\n");
        g_blocked++;
        note_blocked(name);
    } else {
        out("allowed, ret="); outl(ret); out("\n");
    }
}

#define PROBE(name, nr, a, b, c, d, e, f) do { \
        long r_; g_trapped = 0; g_trapped_nr = -1; \
        r_ = SC(nr, a, b, c, d, e, f); \
        report(name, nr, r_); \
        if (r_ >= 0 && closefd) SC(SYS_close, r_, 0, 0, 0, 0, 0); \
    } while (0)

/* Numbers missing from older headers (asm-generic numbering, identical on aarch64 and x86_64 here). */
#ifndef SYS_rseq
#define SYS_rseq 293
#endif
#ifndef SYS_clone3
#define SYS_clone3 435
#endif
#ifndef SYS_close_range
#define SYS_close_range 436
#endif
#ifndef SYS_openat2
#define SYS_openat2 437
#endif
#ifndef SYS_pidfd_open
#define SYS_pidfd_open 434
#endif
#ifndef SYS_faccessat2
#define SYS_faccessat2 439
#endif
#ifndef SYS_epoll_pwait2
#define SYS_epoll_pwait2 441
#endif
#ifndef SYS_futex_waitv
#define SYS_futex_waitv 449
#endif
#ifndef SYS_set_mempolicy_home_node
#define SYS_set_mempolicy_home_node 450
#endif

static void probe_all(void) {
    static char buf[512];
    long pid = SC(SYS_getpid, 0, 0, 0, 0, 0, 0);
    int closefd;

    out("shc-sysprobe " ARCH_NAME ": syscalls used by glibc 2.41 start-up, Box64 and Wine\n");

    closefd = 0;
    /* glibc / dynamic loader start-up */
    PROBE("rseq", SYS_rseq, 0, 0, 0, 0, 0, 0);                        /* registered by glibc >= 2.35 at start-up */
    PROBE("set_robust_list", SYS_set_robust_list, 0, 0, 0, 0, 0, 0);   /* len 0 -> EINVAL if allowed */
    PROBE("get_robust_list", SYS_get_robust_list, 0, buf, buf + 16, 0, 0, 0);
    PROBE("prlimit64", SYS_prlimit64, 0, 7 /*RLIMIT_NOFILE*/, 0, buf, 0, 0);
    PROBE("getrandom", SYS_getrandom, buf, 4, 1 /*GRND_NONBLOCK*/, 0, 0, 0);
    PROBE("set_tid_address", SYS_set_tid_address, buf, 0, 0, 0, 0, 0);
    PROBE("faccessat2", SYS_faccessat2, -100 /*AT_FDCWD*/, "/", 0, 0, 0, 0);
    PROBE("statx", SYS_statx, -100, "/", 0, 0, buf, 0);
    PROBE("clone3 (invalid args)", SYS_clone3, 0, 0, 0, 0, 0, 0);
    PROBE("openat2 (invalid args)", SYS_openat2, -100, 0, 0, 0, 0, 0);
    PROBE("close_range", SYS_close_range, 0x7ffffff0, 0x7fffffff, 0, 0, 0, 0);
    PROBE("membarrier query", SYS_membarrier, 0, 0, 0, 0, 0, 0);
    PROBE("futex_waitv (invalid args)", SYS_futex_waitv, 0, 0, 0, 0, 0, 0);
    PROBE("epoll_pwait2 (bad fd)", SYS_epoll_pwait2, -1, 0, 0, 0, 0, 0);
    PROBE("sched_getaffinity", SYS_sched_getaffinity, 0, 128, buf, 0, 0, 0);
    PROBE("prctl PR_GET_NAME", SYS_prctl, 16, buf, 0, 0, 0, 0);
    PROBE("madvise (no-op)", SYS_madvise, 0, 0, 0, 0, 0, 0);
    PROBE("mremap (invalid args)", SYS_mremap, 0, 0, 0, 0, 0, 0);
    PROBE("personality query", SYS_personality, 0xffffffff, 0, 0, 0, 0, 0);
    PROBE("process_vm_readv (empty)", SYS_process_vm_readv, pid, 0, 0, 0, 0, 0);
    PROBE("copy_file_range (bad fd)", SYS_copy_file_range, -1, 0, -1, 0, 0, 0);
    PROBE("renameat2 (empty paths)", SYS_renameat2, -100, "", -100, "", 0, 0);
    PROBE("unshare(0)", SYS_unshare, 0, 0, 0, 0, 0, 0);
    PROBE("setns (bad fd)", SYS_setns, -1, 0, 0, 0, 0, 0);
    PROBE("io_uring_setup (invalid)", SYS_io_uring_setup, 0, 0, 0, 0, 0, 0);
    PROBE("set_mempolicy_home_node (invalid)", SYS_set_mempolicy_home_node, 0, 0, 0, 0, 0, 0);
    /* wineserver accepts its clients with accept(); bionic (and Android's filter) only has accept4 */
    PROBE("accept (bad fd)", SYS_accept, -1, 0, 0, 0, 0, 0);
    PROBE("accept4 (bad fd)", SYS_accept4, -1, 0, 0, 0, 0, 0);
    closefd = 1;
    PROBE("memfd_create", SYS_memfd_create, "shc", 1 /*MFD_CLOEXEC*/, 0, 0, 0, 0);
    PROBE("pidfd_open", SYS_pidfd_open, pid, 0, 0, 0, 0, 0);
    PROBE("userfaultfd", SYS_userfaultfd, 0x80000 /*O_CLOEXEC*/, 0, 0, 0, 0, 0);
    PROBE("eventfd2", SYS_eventfd2, 0, 0x80000, 0, 0, 0, 0);
    closefd = 0;

    /* Controls: Android's app filter is documented to block these; on desktop Linux they are allowed. */
    PROBE("control: setuid(own uid)", SYS_setuid, SC(SYS_getuid, 0, 0, 0, 0, 0, 0), 0, 0, 0, 0, 0);
    PROBE("control: mount (null args)", SYS_mount, 0, 0, 0, 0, 0, 0);

    out("blocked: "); outl(g_blocked); out(" ["); out(g_blocked_list); out("]\n");
    out("probe done\n");
}

static int trap_selftest(void) {
    /* Filter: trap getppid, allow everything else. Requires no_new_privs. */
    struct sock_filter f[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_getppid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog prog = { (unsigned short)(sizeof f / sizeof f[0]), f };
    long r = SC(SYS_prctl, 38 /*PR_SET_NO_NEW_PRIVS*/, 1, 0, 0, 0, 0);
    if (r != 0) { out("trap-selftest: PR_SET_NO_NEW_PRIVS failed: "); outl(r); out("\n"); return 2; }
    r = SC(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &prog, 0, 0, 0);
    if (r != 0) { out("trap-selftest: seccomp() failed: "); outl(r); out("\n"); return 2; }
    g_trapped = 0;
    r = SC(SYS_getppid, 0, 0, 0, 0, 0, 0);
    if (g_trapped && g_trapped_nr == SYS_getppid && r == SENTINEL) {
        out("trap-selftest: PASS (SIGSYS for syscall "); outl(g_trapped_nr); out(" caught, execution continued)\n");
        return 0;
    }
    out("trap-selftest: FAIL trapped="); outl(g_trapped); out(" nr="); outl(g_trapped_nr); out(" ret="); outl(r); out("\n");
    return 1;
}

static int streq(const char *a, const char *b) { while (*a && *a == *b) { a++; b++; } return *a == *b; }

void cstart(long *sp) {
    long argc = sp[0];
    char **argv = (char **)(sp + 1);
    int rc = 0;
    if (install_handler() != 0) { out("cannot install SIGSYS handler\n"); SC(SYS_exit_group, 3, 0, 0, 0, 0, 0); }
    if (argc > 1 && streq(argv[1], "--trap-selftest")) rc = trap_selftest();
    else probe_all();
    SC(SYS_exit_group, rc, 0, 0, 0, 0, 0);
}
