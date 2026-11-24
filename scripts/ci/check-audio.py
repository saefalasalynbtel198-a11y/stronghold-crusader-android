"""Checks audio recorded by the app's receiver (DisplayCli audio-bridge): format, length, level, gaps, and which test
tones are present (Goertzel). Prints one 'check ...' line per requirement; exit 1 if any fails.
Usage: python3 -I check-audio.py <out dir> <min seconds> <freq> [<freq> ...]
Every frequency must be present in the recording(s); with several frequencies in one stream, Wine mixed them."""
import math
import os
import struct
import sys
import wave

args = sys.argv[1:]
gap_info = "--gaps-info" in args          # under qemu emulation, dropouts are reported but not judged
args = [a for a in args if a != "--gaps-info"]
out, min_s, freqs = args[0], float(args[1]), [float(f) for f in args[2:]]
bad = 0


def check(name, ok, detail=""):
    global bad
    print("check %s: %s%s" % (name, "PASS" if ok else "FAIL", " (" + detail + ")" if detail else ""))
    if not ok:
        bad += 1


def goertzel(x, rate, f):
    k = 2 * math.cos(2 * math.pi * f / rate)
    s1 = s2 = 0.0
    for v in x:
        s0 = v + k * s1 - s2
        s2, s1 = s1, s0
    return math.sqrt(max(0.0, s1 * s1 + s2 * s2 - k * s1 * s2)) / len(x)


files = sorted(f for f in os.listdir(out) if f.endswith(".wav")) if os.path.isdir(out) else []
check("audio streams recorded", len(files) > 0, "%d stream(s)" % len(files))
found = {f: [] for f in freqs}
total_s = 0.0
for name in files:
    with wave.open(os.path.join(out, name)) as w:
        rate, ch, n = w.getframerate(), w.getnchannels(), w.getnframes()
        raw = w.readframes(n)
    samples = struct.unpack("<%dh" % (len(raw) // 2), raw)
    mono = [samples[i] for i in range(0, len(samples), ch)]   # first channel
    dur = n / rate if rate else 0
    total_s = max(total_s, dur)
    nz = [i for i, v in enumerate(mono) if abs(v) > 64]
    rms = math.sqrt(sum(v * v for v in mono) / max(1, len(mono)))
    # longest silent run between the first and the last sound (dropouts)
    gap = 0
    if nz:
        run = 0
        for v in mono[nz[0]:nz[-1]]:
            run = run + 1 if abs(v) <= 64 else 0
            gap = max(gap, run)
    print("%s: %d Hz, %d ch, %.2f s, RMS %.0f, sound from %.2f to %.2f s, longest gap inside %.0f ms" % (
        name, rate, ch, dur, rms, nz[0] / rate if nz else 0, nz[-1] / rate if nz else 0, gap * 1000 / rate if rate else 0))
    if not nz:
        continue
    seg = mono[nz[0]:nz[-1]]
    step = max(1, len(seg) // 48000)          # analyse about one second's worth of samples, spread over the stream
    sub = seg[::step]
    srate = rate / step
    level = {f: goertzel(sub, srate, f) for f in freqs}
    ref = [goertzel(sub, srate, f * 1.37) for f in freqs]       # off-tone reference
    for f in freqs:
        strong = level[f] > 200 and level[f] > 8 * max(ref)
        print("  %g Hz: level %.0f (off-tone %.0f)%s" % (f, level[f], max(ref), " present" if strong else ""))
        if strong:
            found[f].append(name)
    check("%s: 16-bit PCM at a usable rate" % name, rate in (8000, 11025, 22050, 44100, 48000) and ch in (1, 2))
    if gap_info:
        print("info %s: longest dropout %.0f ms (emulated CPU; not judged)" % (name, gap * 1000 / rate))
    else:
        check("%s: no dropout longer than 100 ms while sound plays" % name, gap * 1000 / rate <= 100,
              "%.0f ms" % (gap * 1000 / rate))
for f in freqs:
    check("tone %g Hz reached the app" % f, len(found[f]) > 0, ", ".join(found[f]) or "not found")
check("at least %.1f s of audio" % min_s, total_s >= min_s, "%.2f s" % total_s)
sys.exit(1 if bad else 0)
