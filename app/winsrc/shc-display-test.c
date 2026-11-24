/*
 * shc-display-test: BUILD 3 step 3.2 display test program (32-bit, like the game). Shipped in the APK as an
 * asset and run by the app's Display Test through Box64 + Wine into Xvfb.
 *
 * Draws a 1024x768 borderless window at (0,0), double-buffered with GDI, about 30 times per second:
 *   - four quadrants: red (255,0,0), green (0,255,0), blue (0,0,255), yellow (255,255,0)
 *   - a white 64x64 square moving left/right in the band y = 352..415 (away from the quadrant check points)
 *   - "frame N" as text in the top-left corner
 *   - the frame number as a 16-bit strip in the bottom-left quadrant: 16 cells 16x16 px at y = 736..751,
 *     x = 16 + 20*i, bit 15 first; white = 1, black = 0 (read by the app to measure Wine's frame rate)
 * Input it receives (mouse buttons, wheel, keys, characters; at most 200 lines) is written to stdout as
 * "display-test: input ...", so CI can check that XTEST events from the app's X11 client reach a Windows program.
 * Runs until killed, or for [seconds] if given; exits 42. Progress goes to stdout.
 *   shc-display-test.exe [seconds]
 * Build: i686-w64-mingw32-gcc -O2 -Wall -Werror -static -o shc-display-test.exe shc-display-test.c -lgdi32
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define W 1024
#define H 768
#define BAND_Y 352
#define SQUARE 64
#define BITS_Y 736
#define BITS_X 16
#define BITS_STEP 20
#define BITS_CELL 16

static unsigned g_frame;
static HDC g_mem;
static HBITMAP g_bmp;

static void out(const char *fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  vprintf(fmt, ap);
  va_end(ap);
  fflush(stdout);
}

static void fill(HDC dc, int x, int y, int w, int h, COLORREF c) {
  RECT r = {x, y, x + w, y + h};
  HBRUSH b = CreateSolidBrush(c);
  FillRect(dc, &r, b);
  DeleteObject(b);
}

static void draw_frame(HDC dc) {
  fill(dc, 0, 0, W / 2, H / 2, RGB(255, 0, 0));
  fill(dc, W / 2, 0, W / 2, H / 2, RGB(0, 255, 0));
  fill(dc, 0, H / 2, W / 2, H / 2, RGB(0, 0, 255));
  fill(dc, W / 2, H / 2, W / 2, H / 2, RGB(255, 255, 0));
  int span = W - SQUARE;
  int pos = (int)((g_frame * 8) % (2 * span));
  int x = pos < span ? pos : 2 * span - pos;
  fill(dc, x, BAND_Y, SQUARE, SQUARE, RGB(255, 255, 255));
  char text[32];
  int n = snprintf(text, sizeof text, "frame %u", g_frame);
  SetBkMode(dc, OPAQUE);
  SetBkColor(dc, RGB(0, 0, 0));
  SetTextColor(dc, RGB(255, 255, 255));
  TextOutA(dc, 8, 8, text, n);
  for (int i = 0; i < 16; i++) {
    int bit = (g_frame >> (15 - i)) & 1;
    fill(dc, BITS_X + BITS_STEP * i, BITS_Y, BITS_CELL, BITS_CELL, bit ? RGB(255, 255, 255) : RGB(0, 0, 0));
  }
}

static int g_inputs;

static void input(const char *what, WPARAM wp, LPARAM lp, int pos) {
  if (g_inputs++ >= 200) return;
  if (pos) out("display-test: input %s %d,%d\n", what, (int)(short)LOWORD(lp), (int)(short)HIWORD(lp));
  else out("display-test: input %s 0x%02x\n", what, (unsigned)wp);
}

static LRESULT CALLBACK wndproc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
  switch (msg) {
    case WM_LBUTTONDOWN: input("WM_LBUTTONDOWN", wp, lp, 1); SetFocus(hwnd); return 0;
    case WM_LBUTTONUP: input("WM_LBUTTONUP", wp, lp, 1); return 0;
    case WM_RBUTTONDOWN: input("WM_RBUTTONDOWN", wp, lp, 1); return 0;
    case WM_RBUTTONUP: input("WM_RBUTTONUP", wp, lp, 1); return 0;
    case WM_MOUSEWHEEL: input("WM_MOUSEWHEEL", (WPARAM)(short)HIWORD(wp) & 0xffff, lp, 0); return 0;
    case WM_KEYDOWN: input("WM_KEYDOWN", wp, lp, 0); return 0;
    case WM_KEYUP: input("WM_KEYUP", wp, lp, 0); return 0;
    case WM_CHAR: input("WM_CHAR", wp, lp, 0); return 0;
    default: break;
  }
  if (msg == WM_PAINT) {
    PAINTSTRUCT ps;
    HDC dc = BeginPaint(hwnd, &ps);
    BitBlt(dc, 0, 0, W, H, g_mem, 0, 0, SRCCOPY);
    EndPaint(hwnd, &ps);
    return 0;
  }
  if (msg == WM_ERASEBKGND) return 1;
  if (msg == WM_DESTROY) { PostQuitMessage(0); return 0; }
  return DefWindowProcA(hwnd, msg, wp, lp);
}

int main(int argc, char **argv) {
  DWORD secs = argc > 1 ? (DWORD)atoi(argv[1]) : 0;
  out("display-test: start%s\n", secs ? "" : " (until stopped)");
  WNDCLASSA wc;
  memset(&wc, 0, sizeof wc);
  wc.lpfnWndProc = wndproc;
  wc.hInstance = GetModuleHandleA(NULL);
  wc.hCursor = LoadCursorA(NULL, (LPCSTR)IDC_ARROW);
  wc.lpszClassName = "shc-display-test";
  if (!RegisterClassA(&wc)) { out("display-test: RegisterClass failed %lu\n", GetLastError()); return 2; }
  HWND hwnd = CreateWindowExA(WS_EX_TOPMOST, "shc-display-test", "shc-display-test", WS_POPUP | WS_VISIBLE,
                              0, 0, W, H, NULL, NULL, wc.hInstance, NULL);
  if (!hwnd) { out("display-test: CreateWindowEx failed %lu\n", GetLastError()); return 3; }
  HDC wdc = GetDC(hwnd);
  g_mem = CreateCompatibleDC(wdc);
  g_bmp = CreateCompatibleBitmap(wdc, W, H);
  SelectObject(g_mem, g_bmp);
  ShowWindow(hwnd, SW_SHOW);
  out("display-test: window %p created\n", (void *)hwnd);
  DWORD start = GetTickCount(), last_report = start;
  for (;;) {
    MSG m;
    while (PeekMessageA(&m, NULL, 0, 0, PM_REMOVE)) {
      if (m.message == WM_QUIT) goto done;
      TranslateMessage(&m);
      DispatchMessageA(&m);
    }
    DWORD t0 = GetTickCount();
    draw_frame(g_mem);
    BitBlt(wdc, 0, 0, W, H, g_mem, 0, 0, SRCCOPY);
    GdiFlush();
    if (g_frame == 0) out("display-test: first frame drawn\n");
    g_frame++;
    DWORD now = GetTickCount();
    if (now - last_report >= 10000) {
      out("display-test: %u frames in %lu ms\n", g_frame, (unsigned long)(now - start));
      last_report = now;
    }
    if (secs && now - start >= secs * 1000) break;
    DWORD spent = now - t0;
    if (spent < 33) Sleep(33 - spent);
  }
done:
  out("display-test: done, %u frames\n", g_frame);
  ReleaseDC(hwnd, wdc);
  return 42;
}
