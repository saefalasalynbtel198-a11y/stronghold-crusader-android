/*
 * CI test harness (Linux x86-64 only): installs a seccomp filter that traps rseq and mount with
 * SECCOMP_RET_TRAP, as Android's app filter does for syscalls outside its allowlist, then execs argv[1].
 * Used to reproduce the device failure (glibc start-up killed by SIGSYS, exit 159) and to verify
 * that GLIBC_TUNABLES=glibc.pthread.rseq=0 avoids it. It does not replicate the full Android filter.
 */
#define _GNU_SOURCE
#include <stddef.h>
#include <unistd.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
int main(int c, char **v) {
  struct sock_filter f[] = {
    BPF_STMT(BPF_LD|BPF_W|BPF_ABS, offsetof(struct seccomp_data, nr)),
    BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_rseq, 2, 0),
    BPF_JUMP(BPF_JMP|BPF_JEQ|BPF_K, SYS_mount, 1, 0),
    BPF_STMT(BPF_RET|BPF_K, SECCOMP_RET_ALLOW),
    BPF_STMT(BPF_RET|BPF_K, SECCOMP_RET_TRAP) };
  struct sock_fprog p = { 5, f };
  prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
  if (syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &p)) return 9;
  execv(v[1], v + 1); return 8; }
