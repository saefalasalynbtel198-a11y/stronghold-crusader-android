/*
 * shc-ddraw-input-test: CI-only test program for the Display Resolution setting (0.6.0). Not shipped in the APK.
 *
 * Like the game: a 32-bit DirectDraw program in exclusive fullscreen at a 16-bit display mode, writing its primary
 * surface through Lock/Unlock (cnc-ddraw scales it onto the X screen). It
 *   - lists the display modes DirectDraw offers ("ddraw-input: mode WxH") and says whether the requested one is
 *     among them (cnc-ddraw's inject_resolution adds the X screen size to the list),
 *   - sets the requested mode (default 1024x768) and fills the four quadrants of that mode with the colours of the
 *     runtime's DirectDraw test program: magenta, cyan, white, grey (RGB565 0xF81F 0x07FF 0xFFFF 0x8410),
 *   - writes the mouse buttons it receives with their position in game pixels, as the window messages carry them
 *     after cnc-ddraw (adjmouse) and as GetCursorPos reports them:
 *       "ddraw-input: WM_LBUTTONDOWN x,y cursor x,y"
 * Exits 42 after [seconds].
 *   shc-ddraw-input-test.exe <width> <height> [seconds]
 * Build: i686-w64-mingw32-gcc -O2 -Wall -Werror -static -o shc-ddraw-input-test.exe shc-ddraw-input-test.c -lgdi32
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <ddraw.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int g_w = 1024, g_h = 768, g_inputs;
static LPDIRECTDRAWSURFACE g_primary;
static int g_frames;

static void out(const char *fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  vprintf(fmt, ap);
  va_end(ap);
  fflush(stdout);
}

static void button(const char *what, LPARAM lp) {
  if (g_inputs++ >= 200) return;
  POINT c;
  GetCursorPos(&c);
  out("ddraw-input: %s %d,%d cursor %ld,%ld\n", what, (int)(short)LOWORD(lp), (int)(short)HIWORD(lp), c.x, c.y);
}

static LRESULT CALLBACK wndproc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
  switch (msg) {
    case WM_LBUTTONDOWN: button("WM_LBUTTONDOWN", lp); return 0;
    case WM_LBUTTONUP: button("WM_LBUTTONUP", lp); return 0;
    case WM_RBUTTONDOWN: button("WM_RBUTTONDOWN", lp); return 0;
    case WM_RBUTTONUP: button("WM_RBUTTONUP", lp); return 0;
    case WM_DESTROY: PostQuitMessage(0); return 0;
    default: return DefWindowProcA(hwnd, msg, wp, lp);
  }
}

static HRESULT WINAPI on_mode(LPDDSURFACEDESC d, LPVOID found) {
  if (d->ddpfPixelFormat.dwRGBBitCount == 16) {
    out("ddraw-input: mode %lux%lu\n", d->dwWidth, d->dwHeight);
    if ((int)d->dwWidth == g_w && (int)d->dwHeight == g_h) *(int *)found = 1;
  }
  return DDENUMRET_OK;
}

static void fill(void) {
  DDSURFACEDESC sd;
  memset(&sd, 0, sizeof sd);
  sd.dwSize = sizeof sd;
  HRESULT hr = IDirectDrawSurface_Lock(g_primary, NULL, &sd, DDLOCK_WAIT | DDLOCK_WRITEONLY, NULL);
  if (FAILED(hr)) {
    if (hr == DDERR_SURFACELOST) IDirectDrawSurface_Restore(g_primary);
    if (!g_frames) out("ddraw-input: Lock failed 0x%08lx\n", (unsigned long)hr);
    return;
  }
  static const WORD c[4] = {0xF81F, 0x07FF, 0xFFFF, 0x8410};
  for (DWORD y = 0; y < sd.dwHeight; y++) {
    WORD *row = (WORD *)((BYTE *)sd.lpSurface + y * sd.lPitch);
    for (DWORD x = 0; x < sd.dwWidth; x++) row[x] = c[(x >= sd.dwWidth / 2) + 2 * (y >= sd.dwHeight / 2)];
  }
  IDirectDrawSurface_Unlock(g_primary, NULL);
  if (!g_frames++) out("ddraw-input: primary written (%lux%lu, %lu bpp)\n", sd.dwWidth, sd.dwHeight,
                       sd.ddpfPixelFormat.dwRGBBitCount);
}

int main(int argc, char **argv) {
  if (argc > 2) { g_w = atoi(argv[1]); g_h = atoi(argv[2]); }
  DWORD secs = argc > 3 ? (DWORD)atoi(argv[3]) : 120;
  out("ddraw-input: start, mode %dx%d, %lu s\n", g_w, g_h, secs);
  HMODULE dd = LoadLibraryA("ddraw.dll");
  if (!dd) { out("ddraw-input: LoadLibrary(ddraw.dll) failed %lu\n", GetLastError()); return 5; }
  typedef HRESULT(WINAPI * create_t)(GUID *, LPDIRECTDRAW *, IUnknown *);
  create_t create = (create_t)GetProcAddress(dd, "DirectDrawCreate");
  LPDIRECTDRAW ddraw = NULL;
  HRESULT hr = create ? create(NULL, &ddraw, NULL) : E_FAIL;
  if (FAILED(hr)) { out("ddraw-input: DirectDrawCreate failed 0x%08lx\n", (unsigned long)hr); return 6; }

  WNDCLASSA wc;
  memset(&wc, 0, sizeof wc);
  wc.lpfnWndProc = wndproc;
  wc.hInstance = GetModuleHandleA(NULL);
  wc.hCursor = LoadCursorA(NULL, (LPCSTR)IDC_ARROW);
  wc.lpszClassName = "shc-ddraw-input-test";
  if (!RegisterClassA(&wc)) { out("ddraw-input: RegisterClass failed %lu\n", GetLastError()); return 2; }
  HWND hwnd = CreateWindowExA(WS_EX_TOPMOST, wc.lpszClassName, wc.lpszClassName, WS_POPUP | WS_VISIBLE, 0, 0,
                              g_w, g_h, NULL, NULL, wc.hInstance, NULL);
  if (!hwnd) { out("ddraw-input: CreateWindowEx failed %lu\n", GetLastError()); return 3; }
  ShowWindow(hwnd, SW_SHOW);

  hr = IDirectDraw_SetCooperativeLevel(ddraw, hwnd, DDSCL_EXCLUSIVE | DDSCL_FULLSCREEN);
  out("ddraw-input: SetCooperativeLevel 0x%08lx\n", (unsigned long)hr);
  int listed = 0;
  IDirectDraw_EnumDisplayModes(ddraw, 0, NULL, &listed, on_mode);
  out("ddraw-input: requested mode %dx%d %s\n", g_w, g_h, listed ? "listed" : "NOT listed");
  hr = IDirectDraw_SetDisplayMode(ddraw, g_w, g_h, 16);
  out("ddraw-input: SetDisplayMode(%d,%d,16) 0x%08lx\n", g_w, g_h, (unsigned long)hr);
  if (FAILED(hr)) return 7;
  DDSURFACEDESC sd;
  memset(&sd, 0, sizeof sd);
  sd.dwSize = sizeof sd;
  sd.dwFlags = DDSD_CAPS;
  sd.ddsCaps.dwCaps = DDSCAPS_PRIMARYSURFACE;
  hr = IDirectDraw_CreateSurface(ddraw, &sd, &g_primary, NULL);
  out("ddraw-input: CreateSurface(primary) 0x%08lx\n", (unsigned long)hr);
  if (FAILED(hr)) return 8;

  DWORD end = GetTickCount() + secs * 1000;
  while ((LONG)(end - GetTickCount()) > 0) {
    MSG m;
    while (PeekMessageA(&m, NULL, 0, 0, PM_REMOVE)) {
      if (m.message == WM_QUIT) goto done;
      TranslateMessage(&m);
      DispatchMessageA(&m);
    }
    fill();
    Sleep(50);
  }
done:
  out("ddraw-input: done, %d frames, %d buttons\n", g_frames, g_inputs);
  IDirectDrawSurface_Release(g_primary);
  IDirectDraw_RestoreDisplayMode(ddraw);
  IDirectDraw_Release(ddraw);
  return g_frames ? 42 : 9;
}
