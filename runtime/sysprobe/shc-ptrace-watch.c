/*
 * shc-ptrace-watch: run a command and report how every process in its tree ends.
 *
 *   shc-ptrace-watch [--] cmd args...
 *
 * Diagnostic for BUILD 3 (Wine on Android). The command and every process it forks are traced with ptrace
 * (fork/vfork/clone/exec events only; no syscall tracing). For each process the watcher prints, on stderr,
 * lines starting with "[watch]":
 *   - fork / exec events with the new command line,
 *   - SIGSYS immediately (seccomp trap: syscall number, arch, call address),
 *   - SIGHUP/SIGINT/SIGQUIT/SIGTERM/SIGABRT/SIGPIPE immediately, with the sending pid,
 *   - the exit status, or the fatal signal plus the last signal seen for that process.
 * Signals are re-delivered unchanged, so the traced programs behave as without the watcher, apart from speed.
 * The watcher exits when every traced process has ended, with the first process's exit status
 * (128 + signal number if it was killed). If ptrace is unavailable the command runs untraced.
 *
 * It does not change, suppress or fake any result; it only observes.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/ptrace.h>
#include <sys/types.h>
#include <sys/wait.h>

#ifndef PTRACE_O_EXITKILL
#define PTRACE_O_EXITKILL (1 << 20)
#endif
#ifndef SYS_SECCOMP
#define SYS_SECCOMP 1
#endif

#define MAXP 8192

struct proc {
  pid_t pid;
  int used, thread, fresh, segv;
  int lastsig, lastcode;
  void *lastaddr;
  char cmd[160];
};

static struct proc procs[MAXP];
static struct timespec t0;

static void logf_(const char *fmt, ...) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  long ms = (t.tv_sec - t0.tv_sec) * 1000 + (t.tv_nsec - t0.tv_nsec) / 1000000;
  char buf[512];
  int n = snprintf(buf, sizeof buf, "[watch %6ld ms] ", ms);
  va_list ap;
  va_start(ap, fmt);
  n += vsnprintf(buf + n, sizeof buf - n, fmt, ap);
  va_end(ap);
  if (n > (int)sizeof buf - 2) n = sizeof buf - 2;
  buf[n++] = '\n';
  ssize_t w = write(2, buf, n);
  (void)w;
}

static struct proc *find(pid_t pid, int create) {
  struct proc *free_slot = NULL;
  for (int i = 0; i < MAXP; i++) {
    if (procs[i].used && procs[i].pid == pid) return &procs[i];
    if (!procs[i].used && !free_slot) free_slot = &procs[i];
  }
  if (!create || !free_slot) return NULL;
  memset(free_slot, 0, sizeof *free_slot);
  free_slot->used = 1;
  free_slot->pid = pid;
  free_slot->fresh = 1;
  return free_slot;
}

static void read_cmd(pid_t pid, char *out, size_t len) {
  char path[64];
  snprintf(path, sizeof path, "/proc/%d/cmdline", (int)pid);
  int fd = open(path, O_RDONLY | O_CLOEXEC);
  out[0] = 0;
  if (fd < 0) return;
  ssize_t n = read(fd, out, len - 1);
  close(fd);
  if (n <= 0) { out[0] = 0; return; }
  out[n] = 0;
  for (ssize_t i = 0; i < n - 1; i++) if (!out[i]) out[i] = ' ';
}

static const char *signame(int sig) {
  switch (sig) {
    case SIGHUP: return "SIGHUP"; case SIGINT: return "SIGINT"; case SIGQUIT: return "SIGQUIT";
    case SIGILL: return "SIGILL"; case SIGTRAP: return "SIGTRAP"; case SIGABRT: return "SIGABRT";
    case SIGBUS: return "SIGBUS"; case SIGFPE: return "SIGFPE"; case SIGKILL: return "SIGKILL";
    case SIGUSR1: return "SIGUSR1"; case SIGSEGV: return "SIGSEGV"; case SIGUSR2: return "SIGUSR2";
    case SIGPIPE: return "SIGPIPE"; case SIGALRM: return "SIGALRM"; case SIGTERM: return "SIGTERM";
    case SIGCHLD: return "SIGCHLD"; case SIGCONT: return "SIGCONT"; case SIGSTOP: return "SIGSTOP";
    case SIGSYS: return "SIGSYS"; default: return "signal";
  }
}

int main(int argc, char **argv) {
  clock_gettime(CLOCK_MONOTONIC, &t0);
  int a = 1;
  if (a < argc && !strcmp(argv[a], "--")) a++;
  if (a >= argc) { fprintf(stderr, "usage: %s [--] cmd args...\n", argv[0]); return 2; }

  int sync[2];
  if (pipe2(sync, O_CLOEXEC)) { perror("pipe2"); return 2; }
  pid_t child = fork();
  if (child < 0) { perror("fork"); return 2; }
  if (child == 0) {
    close(sync[0]);
    int traced = ptrace(PTRACE_TRACEME, 0, NULL, NULL) == 0;
    char st = traced ? 'T' : 'U';
    ssize_t w = write(sync[1], &st, 1);
    (void)w;
    if (traced) raise(SIGSTOP);
    execvp(argv[a], argv + a);
    fprintf(stderr, "[watch] exec %s failed: %s\n", argv[a], strerror(errno));
    _exit(127);
  }
  close(sync[1]);
  char st = 0;
  if (read(sync[0], &st, 1) != 1) {
    /* the child died before it could report: ptrace itself is not allowed here. Run the command untraced. */
    int status = 0;
    waitpid(child, &status, 0);
    logf_("ptrace(PTRACE_TRACEME) killed the helper (%s); running the command untraced",
          WIFSIGNALED(status) ? signame(WTERMSIG(status)) : "exited");
    execvp(argv[a], argv + a);
    logf_("exec %s failed: %s", argv[a], strerror(errno));
    return 127;
  }
  close(sync[0]);

  if (st != 'T') {
    logf_("ptrace unavailable (PTRACE_TRACEME failed); running untraced");
    int status;
    while (waitpid(child, &status, 0) < 0 && errno == EINTR) {}
    if (WIFEXITED(status)) { logf_("pid %d exited %d (untraced)", (int)child, WEXITSTATUS(status)); return WEXITSTATUS(status); }
    logf_("pid %d killed by %s (%d) (untraced)", (int)child, signame(WTERMSIG(status)), WTERMSIG(status));
    return 128 + WTERMSIG(status);
  }

  int main_status = -1, options_set = 0;
  struct proc *p0 = find(child, 1);
  snprintf(p0->cmd, sizeof p0->cmd, "%s", argv[a]);
  logf_("tracing pid %d: %s", (int)child, argv[a]);

  for (;;) {
    int status;
    pid_t pid = waitpid(-1, &status, __WALL);
    if (pid < 0) {
      if (errno == EINTR) continue;
      break;  /* ECHILD: nothing left to trace */
    }
    struct proc *p = find(pid, 1);
    if (!p) { ptrace(PTRACE_CONT, pid, NULL, NULL); continue; }

    if (WIFEXITED(status) || WIFSIGNALED(status)) {
      if (!p->thread) {
        if (WIFEXITED(status))
          logf_("pid %d exited with code %d [%s]", (int)pid, WEXITSTATUS(status), p->cmd);
        else
          logf_("pid %d KILLED by %s (%d) [%s]; last signal seen: %s (%d) code %d addr %p; SIGSEGV seen %d times",
                (int)pid, signame(WTERMSIG(status)), WTERMSIG(status), p->cmd, signame(p->lastsig), p->lastsig,
                p->lastcode, p->lastaddr, p->segv);
      }
      if (pid == child) main_status = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
      p->used = 0;
      continue;
    }
    if (!WIFSTOPPED(status)) continue;

    int sig = WSTOPSIG(status);
    int event = (unsigned)status >> 16;
    if (!options_set && pid == child) {
      ptrace(PTRACE_SETOPTIONS, pid, NULL,
             (void *)(long)(PTRACE_O_TRACEFORK | PTRACE_O_TRACEVFORK | PTRACE_O_TRACECLONE | PTRACE_O_TRACEEXEC |
                            PTRACE_O_EXITKILL));
      options_set = 1;
    }
    if (sig == SIGTRAP && event) {
      unsigned long msg = 0;
      ptrace(PTRACE_GETEVENTMSG, pid, NULL, &msg);
      if (event == PTRACE_EVENT_FORK || event == PTRACE_EVENT_VFORK || event == PTRACE_EVENT_CLONE) {
        struct proc *c = find((pid_t)msg, 1);
        if (c) {
          c->thread = event == PTRACE_EVENT_CLONE;
          if (!c->cmd[0]) snprintf(c->cmd, sizeof c->cmd, "%s", p->cmd);
          if (!c->thread) logf_("pid %d %s -> new pid %d", (int)pid, event == PTRACE_EVENT_VFORK ? "vfork" : "fork", (int)msg);
        }
      } else if (event == PTRACE_EVENT_EXEC) {
        read_cmd(pid, p->cmd, sizeof p->cmd);
        p->thread = 0;
        logf_("pid %d exec: %s", (int)pid, p->cmd);
      }
      p->fresh = 0;
      ptrace(PTRACE_CONT, pid, NULL, NULL);
      continue;
    }
    if (sig == SIGSTOP && p->fresh) {  /* initial stop of a newly traced process or thread */
      p->fresh = 0;
      ptrace(PTRACE_CONT, pid, NULL, NULL);
      continue;
    }
    p->fresh = 0;
    siginfo_t si;
    memset(&si, 0, sizeof si);
    if (ptrace(PTRACE_GETSIGINFO, pid, NULL, &si) == 0) {
      p->lastsig = sig;
      p->lastcode = si.si_code;
      p->lastaddr = si.si_addr;
      if (sig == SIGSEGV) p->segv++;
      if (sig == SIGSYS) {
        logf_("pid %d SIGSYS: si_code %d%s, syscall %d, arch 0x%x, call address %p [%s]", (int)pid, si.si_code,
              si.si_code == SYS_SECCOMP ? " (seccomp)" : "", si.si_syscall, si.si_arch, si.si_call_addr, p->cmd);
      } else if (sig == SIGHUP || sig == SIGINT || sig == SIGQUIT || sig == SIGTERM || sig == SIGABRT ||
                 sig == SIGPIPE) {
        logf_("pid %d received %s (%d): si_code %d, sent by pid %d uid %d [%s]", (int)pid, signame(sig), sig,
              si.si_code, (int)si.si_pid, (int)si.si_uid, p->cmd);
      }
    } else if (sig == SIGSTOP || sig == SIGTSTP || sig == SIGTTIN || sig == SIGTTOU) {
      /* group-stop: leave the process running instead of stopping it (no job control here) */
      ptrace(PTRACE_CONT, pid, NULL, NULL);
      continue;
    }
    ptrace(PTRACE_CONT, pid, NULL, (void *)(long)sig);
  }
  logf_("all traced processes ended; first process status %d", main_status);
  return main_status < 0 ? 1 : main_status;
}
