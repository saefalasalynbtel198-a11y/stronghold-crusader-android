/*
 * shc-gui-smoke: BUILD 3 phase 3 feasibility test (CI only for now). Opens a 1024x768 borderless window at
 * (0,0) and fills it with four known colour blocks, so the X server's framebuffer can be checked pixel by pixel.
 *
 *   shc-gui-smoke.exe gdi   [seconds]   GDI FillRect (Wine GDI -> winex11 -> X server)
 *   shc-gui-smoke.exe ddraw [seconds]   DirectDraw 1024x768x16 primary surface, written through Lock/Unlock,
 *                                       as the game does; with cnc-ddraw's ddraw.dll next to the exe this
 *                                       exercises cnc-ddraw's renderer
 *
 * Quadrants (top-left, top-right, bottom-left, bottom-right):
 *   gdi:   red (255,0,0), green (0,255,0), blue (0,0,255), yellow (255,255,0)
 *   ddraw: magenta 0xF81F, cyan 0x07FF, white 0xFFFF, grey 0x8410 (RGB565)
 * After the first successful paint/write it stays up for [seconds] more (default 120), pumping messages,
 * then exits 42 (startup under emulation can take minutes; at most 15 minutes are waited for the first paint).
 * Progress goes to stdout.
 * Build: i686-w64-mingw32-gcc -O2 -Wall -Werror -static -o shc-gui-smoke.exe shc-gui-smoke.c -lgdi32
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <ddraw.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define W 1024
#define H 768

static int g_painted;

static void out(const char *fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  vprintf(fmt, ap);
  va_end(ap);
  fflush(stdout);
}

static LRESULT CALLBACK wndproc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
  if (msg == WM_PAINT && GetWindowLongPtrA(hwnd, GWLP_USERDATA) == 1) {
    PAINTSTRUCT ps;
    HDC dc = BeginPaint(hwnd, &ps);
    static const COLORREF c[4] = {RGB(255, 0, 0), RGB(0, 255, 0), RGB(0, 0, 255), RGB(255, 255, 0)};
    for (int i = 0; i < 4; i++) {
      RECT r = {(i & 1) * W / 2, (i >> 1) * H / 2, (i & 1) * W / 2 + W / 2, (i >> 1) * H / 2 + H / 2};
      HBRUSH b = CreateSolidBrush(c[i]);
      FillRect(dc, &r, b);
      DeleteObject(b);
    }
    EndPaint(hwnd, &ps);
    if (!g_painted++) out("gui-smoke: gdi painted\n");
    return 0;
  }
  if (msg == WM_DESTROY) { PostQuitMessage(0); return 0; }
  return DefWindowProcA(hwnd, msg, wp, lp);
}

static HWND make_window(int gdi) {
  WNDCLASSA wc;
  memset(&wc, 0, sizeof wc);
  wc.lpfnWndProc = wndproc;
  wc.hInstance = GetModuleHandleA(NULL);
  wc.hCursor = LoadCursorA(NULL, (LPCSTR)IDC_ARROW);
  wc.lpszClassName = "shc-gui-smoke";
  if (!RegisterClassA(&wc)) { out("gui-smoke: RegisterClass failed %lu\n", GetLastError()); exit(2); }
  HWND hwnd = CreateWindowExA(WS_EX_TOPMOST, "shc-gui-smoke", "shc-gui-smoke", WS_POPUP | WS_VISIBLE, 0, 0, W, H,
                              NULL, NULL, wc.hInstance, NULL);
  if (!hwnd) { out("gui-smoke: CreateWindowEx failed %lu\n", GetLastError()); exit(3); }
  SetWindowLongPtrA(hwnd, GWLP_USERDATA, gdi ? 1 : 0);
  RECT r;
  GetWindowRect(hwnd, &r);
  out("gui-smoke: window %p at %ld,%ld size %ldx%ld\n", (void *)hwnd, r.left, r.top, r.right - r.left, r.bottom - r.top);
  ShowWindow(hwnd, SW_SHOW);
  UpdateWindow(hwnd);
  return hwnd;
}

static int pump(DWORD ms, void (*tick)(void), int *until_nonzero) {
  DWORD end = GetTickCount() + ms;
  while ((LONG)(end - GetTickCount()) > 0) {
    if (until_nonzero && *until_nonzero) return 1;
    MSG m;
    while (PeekMessageA(&m, NULL, 0, 0, PM_REMOVE)) {
      if (m.message == WM_QUIT) return 0;
      TranslateMessage(&m);
      DispatchMessageA(&m);
    }
    if (tick) tick();
    Sleep(50);
  }
  return 1;
}

static LPDIRECTDRAWSURFACE g_primary;
static int g_frames;

static void ddraw_fill(void) {
  DDSURFACEDESC sd;
  memset(&sd, 0, sizeof sd);
  sd.dwSize = sizeof sd;
  HRESULT hr = IDirectDrawSurface_Lock(g_primary, NULL, &sd, DDLOCK_WAIT | DDLOCK_WRITEONLY, NULL);
  if (FAILED(hr)) {
    if (hr == DDERR_SURFACELOST) IDirectDrawSurface_Restore(g_primary);
    if (!g_frames) out("gui-smoke: Lock failed 0x%08lx\n", (unsigned long)hr);
    return;
  }
  static const WORD c[4] = {0xF81F, 0x07FF, 0xFFFF, 0x8410};
  for (DWORD y = 0; y < sd.dwHeight; y++) {
    WORD *row = (WORD *)((BYTE *)sd.lpSurface + y * sd.lPitch);
    for (DWORD x = 0; x < sd.dwWidth; x++) row[x] = c[(x >= W / 2) + 2 * (y >= H / 2)];
  }
  IDirectDrawSurface_Unlock(g_primary, NULL);
  if (!g_frames++) out("gui-smoke: ddraw primary written (%lux%lu, pitch %ld, %lu bpp)\n", sd.dwWidth, sd.dwHeight,
                       sd.lPitch, sd.ddpfPixelFormat.dwRGBBitCount);
}

int main(int argc, char **argv) {
  const char *mode = argc > 1 ? argv[1] : "gdi";
  DWORD secs = argc > 2 ? (DWORD)atoi(argv[2]) : 120;
  out("gui-smoke: mode %s, %lu s\n", mode, secs);
  if (!strcmp(mode, "gdi")) {
    make_window(1);
    pump(15 * 60 * 1000, NULL, &g_painted);
    if (g_painted) pump(secs * 1000, NULL, NULL);
    out("gui-smoke: done, painted %d times\n", g_painted);
    return g_painted ? 42 : 4;
  }
  if (strcmp(mode, "ddraw")) { out("gui-smoke: unknown mode\n"); return 2; }

  HMODULE dd = LoadLibraryA("ddraw.dll");
  if (!dd) { out("gui-smoke: LoadLibrary(ddraw.dll) failed %lu\n", GetLastError()); return 5; }
  char path[MAX_PATH] = "";
  GetModuleFileNameA(dd, path, sizeof path);
  out("gui-smoke: ddraw.dll = %s\n", path);
  typedef HRESULT(WINAPI * create_t)(GUID *, LPDIRECTDRAW *, IUnknown *);
  create_t create = (create_t)GetProcAddress(dd, "DirectDrawCreate");
  LPDIRECTDRAW ddraw = NULL;
  HRESULT hr = create ? create(NULL, &ddraw, NULL) : E_FAIL;
  if (FAILED(hr)) { out("gui-smoke: DirectDrawCreate failed 0x%08lx\n", (unsigned long)hr); return 6; }
  HWND hwnd = make_window(0);
  hr = IDirectDraw_SetCooperativeLevel(ddraw, hwnd, DDSCL_EXCLUSIVE | DDSCL_FULLSCREEN);
  out("gui-smoke: SetCooperativeLevel 0x%08lx\n", (unsigned long)hr);
  hr = IDirectDraw_SetDisplayMode(ddraw, W, H, 16);
  out("gui-smoke: SetDisplayMode(1024,768,16) 0x%08lx\n", (unsigned long)hr);
  if (FAILED(hr)) return 7;
  DDSURFACEDESC sd;
  memset(&sd, 0, sizeof sd);
  sd.dwSize = sizeof sd;
  sd.dwFlags = DDSD_CAPS;
  sd.ddsCaps.dwCaps = DDSCAPS_PRIMARYSURFACE;
  hr = IDirectDraw_CreateSurface(ddraw, &sd, &g_primary, NULL);
  out("gui-smoke: CreateSurface(primary) 0x%08lx\n", (unsigned long)hr);
  if (FAILED(hr)) return 8;
  ddraw_fill();
  pump(15 * 60 * 1000, ddraw_fill, &g_frames);
  if (g_frames) pump(secs * 1000, ddraw_fill, NULL);
  out("gui-smoke: done, %d frames written\n", g_frames);
  IDirectDrawSurface_Release(g_primary);
  IDirectDraw_RestoreDisplayMode(ddraw);
  IDirectDraw_Release(ddraw);
  return g_frames ? 42 : 9;
}
