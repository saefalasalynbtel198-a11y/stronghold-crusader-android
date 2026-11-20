/*
 * CI test harness (Linux x86-64 only): installs a seccomp filter that traps syscalls with
 * SECCOMP_RET_TRAP, as Android's app filter does for syscalls outside its allowlist, then execs argv[2].
 *
 *   sim-android-filter rseq      <cmd...>   traps rseq + mount (first device diagnosis)
 *   sim-android-filter android16 <cmd...>   traps the syscalls the seccomp probe reported as BLOCKED on
 *                                           Xiaomi 25053PC47G / Android 16 (SDK 36), plus clone3 and
 *                                           fchmodat2, which older Android releases also block
 *
 * It does not replicate Android's full allowlist; it reproduces the observed failures.
 */
#define _GNU_SOURCE
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <linux/filter.h>
#include <linux/seccomp.h>

#ifndef SYS_rseq
#define SYS_rseq 334
#endif
#ifndef SYS_clone3
#define SYS_clone3 435
#endif
#ifndef SYS_openat2
#define SYS_openat2 437
#endif
#ifndef SYS_faccessat2
#define SYS_faccessat2 439
#endif
#ifndef SYS_futex_waitv
#define SYS_futex_waitv 449
#endif
#ifndef SYS_set_mempolicy_home_node
#define SYS_set_mempolicy_home_node 450
#endif
#ifndef SYS_fchmodat2
#define SYS_fchmodat2 452
#endif

int main(int c, char **v) {
  static const int rseq_set[] = { SYS_rseq, SYS_mount };
  static const int a16_set[] = { SYS_rseq, SYS_set_robust_list, SYS_get_robust_list, SYS_faccessat2,
                                 SYS_openat2, SYS_futex_waitv, SYS_io_uring_setup, SYS_set_mempolicy_home_node,
                                 SYS_mount, SYS_clone3, SYS_fchmodat2 };
  const int *set; int n;
  if (c < 3) { fprintf(stderr, "usage: %s rseq|android16 cmd...\n", v[0]); return 2; }
  if (!strcmp(v[1], "rseq")) { set = rseq_set; n = 2; }
  else if (!strcmp(v[1], "android16")) { set = a16_set; n = (int)(sizeof a16_set / sizeof a16_set[0]); }
  else return 2;

  struct sock_filter f[64]; int k = 0;
  f[k++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr));
  for (int i = 0; i < n; i++)  /* jump to TRAP (last instruction) when nr matches */
    f[k++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (unsigned)set[i], (unsigned char)(n - i), 0);
  f[k++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
  f[k++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP);
  struct sock_fprog p = { (unsigned short)k, f };
  if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)) return 9;
  if (syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &p)) return 9;
  execv(v[2], v + 2);
  return 8;
}
