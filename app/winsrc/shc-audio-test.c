/*
 * shc-audio-test: CI test program for the game-sound path (not shipped in the APK). Plays two looping tones at the same
 * time through DirectSound, as the game's Miles Sound System does: 440 Hz (22050 Hz mono) and 880 Hz (44100 Hz stereo),
 * so Wine has to resample and mix them. Reports the play cursors (they only advance if the output consumes audio).
 *   shc-audio-test.exe [seconds]      exit 42: played; 43: no sound device (DirectSoundCreate failed); other: error
 * Build: i686-w64-mingw32-gcc -O2 -Wall -Werror -static -o shc-audio-test.exe shc-audio-test.c -ldsound -ldxguid -lm
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <mmsystem.h>
#include <dsound.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>

static LPDIRECTSOUNDBUFFER tone(LPDIRECTSOUND ds, int freq, int rate, int channels, double amp) {
    WAVEFORMATEX wf;
    memset(&wf, 0, sizeof wf);
    wf.wFormatTag = WAVE_FORMAT_PCM;
    wf.nChannels = channels;
    wf.nSamplesPerSec = rate;
    wf.wBitsPerSample = 16;
    wf.nBlockAlign = channels * 2;
    wf.nAvgBytesPerSec = rate * wf.nBlockAlign;
    DSBUFFERDESC d;
    memset(&d, 0, sizeof d);
    d.dwSize = sizeof d;
    d.dwFlags = DSBCAPS_GLOBALFOCUS | DSBCAPS_GETCURRENTPOSITION2;
    d.dwBufferBytes = wf.nAvgBytesPerSec;            /* one second: a whole number of cycles, so it loops cleanly */
    d.lpwfxFormat = &wf;
    LPDIRECTSOUNDBUFFER b = NULL;
    HRESULT hr = IDirectSound_CreateSoundBuffer(ds, &d, &b, NULL);
    if (FAILED(hr)) { printf("audio-test: CreateSoundBuffer(%d Hz) failed 0x%08lx\n", rate, (unsigned long)hr); return NULL; }
    void *p1, *p2;
    DWORD n1, n2;
    if (FAILED(IDirectSoundBuffer_Lock(b, 0, d.dwBufferBytes, &p1, &n1, &p2, &n2, 0))) return NULL;
    short *s = p1;
    for (DWORD i = 0; i < n1 / wf.nBlockAlign; i++) {
        short v = (short)(amp * sin(2 * M_PI * freq * (double)i / rate));
        for (int c = 0; c < channels; c++) s[i * channels + c] = v;
    }
    IDirectSoundBuffer_Unlock(b, p1, n1, p2, n2);
    IDirectSoundBuffer_Play(b, 0, 0, DSBPLAY_LOOPING);
    printf("audio-test: playing %d Hz (%d Hz, %d ch)\n", freq, rate, channels);
    return b;
}

int main(int argc, char **argv) {
    int secs = argc > 1 ? atoi(argv[1]) : 10;
    setvbuf(stdout, NULL, _IONBF, 0);
    HWND hwnd = CreateWindowExA(0, "STATIC", "shc-audio-test", WS_POPUP, 0, 0, 16, 16, NULL, NULL, GetModuleHandleA(NULL), NULL);
    LPDIRECTSOUND ds = NULL;
    HRESULT hr = DirectSoundCreate(NULL, &ds, NULL);
    if (FAILED(hr)) {
        printf("audio-test: DirectSoundCreate failed 0x%08lx (no sound device)\n", (unsigned long)hr);
        return 43;
    }
    IDirectSound_SetCooperativeLevel(ds, hwnd, DSSCL_PRIORITY);
    LPDIRECTSOUNDBUFFER a = tone(ds, 440, 22050, 1, 9000), b = tone(ds, 880, 44100, 2, 9000);
    if (!a || !b) return 3;
    DWORD start = GetTickCount(), lastA = 0, lastB = 0, movedA = 0, movedB = 0;
    while (GetTickCount() - start < (DWORD)secs * 1000) {
        MSG m;
        while (PeekMessageA(&m, NULL, 0, 0, PM_REMOVE)) DispatchMessageA(&m);
        Sleep(500);
        DWORD pa, pb, w;
        IDirectSoundBuffer_GetCurrentPosition(a, &pa, &w);
        IDirectSoundBuffer_GetCurrentPosition(b, &pb, &w);
        if (pa != lastA) movedA++;
        if (pb != lastB) movedB++;
        lastA = pa;
        lastB = pb;
        printf("audio-test: t=%lu ms play cursors %lu / %lu\n", GetTickCount() - start, pa, pb);
    }
    IDirectSoundBuffer_Stop(a);
    IDirectSoundBuffer_Stop(b);
    IDirectSoundBuffer_Release(a);
    IDirectSoundBuffer_Release(b);
    IDirectSound_Release(ds);
    printf("audio-test: done; play cursors moved %lu and %lu times\n", movedA, movedB);
    return movedA > 2 && movedB > 2 ? 42 : 4;
}
