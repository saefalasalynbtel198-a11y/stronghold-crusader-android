/*
 * shc-pe-smoke — minimal Windows console program for the BUILD 3 Wine smoke test.
 * Built twice with mingw-w64: 32-bit (i686, exercises WoW64 like the game) and 64-bit.
 *
 * Checks, printing one "ok"/"FAIL" line each:
 *   - process start and pointer size
 *   - file create/write/read-back in a directory given on the command line (default: current dir)
 *   - %TEMP% resolution and a file there
 *   - CreateProcess of itself with "--child N", WaitForSingleObject, GetExitCodeProcess == N
 *   - LoadLibrary("ddraw.dll") (informational: DirectDraw availability, never fails the test)
 * Exit code 42 and the line "pe-smoke ok" when every required check passed; 1 otherwise.
 */
#include <windows.h>
#include <stdio.h>
#include <string.h>

static int failures;

static void check(int ok, const char *what) {
    printf("%s: %s\n", what, ok ? "ok" : "FAIL");
    if (!ok) {
        failures++;
        printf("  GetLastError=%lu\n", (unsigned long)GetLastError());
    }
    fflush(stdout);
}

static int write_and_read(const char *dir, const char *name) {
    char path[MAX_PATH * 2];
    const char payload[] = "stronghold-crusader-android pe smoke";
    char buf[64] = {0};
    DWORD n = 0;
    snprintf(path, sizeof path, "%s\\%s", dir, name);
    HANDLE h = CreateFileA(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (h == INVALID_HANDLE_VALUE) return 0;
    BOOL w = WriteFile(h, payload, (DWORD)strlen(payload), &n, NULL);
    CloseHandle(h);
    if (!w || n != strlen(payload)) return 0;
    h = CreateFileA(path, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (h == INVALID_HANDLE_VALUE) return 0;
    BOOL r = ReadFile(h, buf, sizeof buf - 1, &n, NULL);
    CloseHandle(h);
    DeleteFileA(path);
    return r && strcmp(buf, payload) == 0;
}

int main(int argc, char **argv) {
    if (argc == 3 && strcmp(argv[1], "--child") == 0) return atoi(argv[2]);

    printf("shc-pe-smoke %d-bit, pid %lu\n", (int)(sizeof(void *) * 8), (unsigned long)GetCurrentProcessId());
    fflush(stdout);
    check(1, "process start");

    char dir[MAX_PATH] = ".";
    if (argc > 1) lstrcpynA(dir, argv[1], MAX_PATH);
    else GetCurrentDirectoryA(MAX_PATH, dir);
    printf("work dir: %s\n", dir);
    check(write_and_read(dir, "shc-pe-smoke.tmp"), "file write/read");

    char temp[MAX_PATH] = {0};
    DWORD tl = GetTempPathA(MAX_PATH, temp);
    if (tl > 0 && temp[tl - 1] == '\\') temp[tl - 1] = 0;
    printf("temp dir: %s\n", temp);
    check(tl > 0 && write_and_read(temp, "shc-pe-smoke-temp.tmp"), "temp file");

    char self[MAX_PATH], cmd[MAX_PATH * 2];
    GetModuleFileNameA(NULL, self, MAX_PATH);
    snprintf(cmd, sizeof cmd, "\"%s\" --child 7", self);
    STARTUPINFOA si; PROCESS_INFORMATION pi; DWORD code = 0;
    memset(&si, 0, sizeof si); si.cb = sizeof si;
    memset(&pi, 0, sizeof pi);
    BOOL created = CreateProcessA(NULL, cmd, NULL, NULL, FALSE, 0, NULL, NULL, &si, &pi);
    if (created) {
        WaitForSingleObject(pi.hProcess, 60000);
        GetExitCodeProcess(pi.hProcess, &code);
        CloseHandle(pi.hThread);
        CloseHandle(pi.hProcess);
    }
    printf("child exit code: %lu\n", (unsigned long)code);
    check(created && code == 7, "child process");

    HMODULE dd = LoadLibraryA("ddraw.dll");
    printf("ddraw.dll: %s (informational)\n", dd ? "loaded" : "not loadable");
    if (dd) FreeLibrary(dd);

    if (failures == 0) {
        printf("pe-smoke ok\n");
        fflush(stdout);
        return 42;
    }
    printf("pe-smoke FAILED (%d checks)\n", failures);
    return 1;
}
