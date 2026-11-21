#!/usr/bin/env python3
"""Reads Xvfb's -fbdir framebuffer (XWD format, Xvfb_screen0), checks colour blocks, writes a PNG.

    check-fb.py <Xvfb_screen0> <expect: gdi|ddraw|none> [out.png]

Exit 0 when every quadrant centre (and 8 more points per quadrant) matches the expected colour within a
tolerance of 8 per channel; 1 otherwise. Always prints what it found. Standard library only.
"""
import struct
import sys
import zlib

EXPECT = {
    'gdi': [(255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 0)],
    # RGB565 F81F, 07FF, FFFF, 8410 expanded to 8 bits (top bits replicated; allow either expansion)
    'ddraw': [(255, 0, 255), (0, 255, 255), (255, 255, 255), (132, 130, 132)],
}
NAMES = ['top-left', 'top-right', 'bottom-left', 'bottom-right']


def read_xwd(path):
    data = open(path, 'rb').read()
    h = struct.unpack('>25I', data[:100])
    header_size, version, fmt, depth, width, height = h[0], h[1], h[2], h[3], h[4], h[5]
    byte_order, bpp, bytes_per_line, ncolors = h[7], h[11], h[12], h[19]
    rmask, gmask, bmask = h[14], h[15], h[16]
    if version != 7 or bpp != 32:
        raise SystemExit('unsupported XWD: version %d bpp %d depth %d' % (version, bpp, depth))
    off = header_size + ncolors * 12
    return dict(data=data, off=off, w=width, h=height, bpl=bytes_per_line, le=(byte_order == 0),
                masks=(rmask, gmask, bmask), depth=depth)


def shift(mask):
    s = 0
    while mask and not mask & 1:
        mask >>= 1
        s += 1
    return s


def pixel(fb, x, y):
    o = fb['off'] + y * fb['bpl'] + x * 4
    v = struct.unpack('<I' if fb['le'] else '>I', fb['data'][o:o + 4])[0]
    return tuple((v & m) >> shift(m) for m in fb['masks'])


def write_png(fb, path):
    rows = bytearray()
    for y in range(fb['h']):
        rows.append(0)
        for x in range(fb['w']):
            rows.extend(pixel(fb, x, y))
    def chunk(t, d):
        c = struct.pack('>I', len(d)) + t + d
        return c + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', fb['w'], fb['h'], 8, 2, 0, 0, 0)) \
        + chunk(b'IDAT', zlib.compress(bytes(rows), 6)) + chunk(b'IEND', b'')
    open(path, 'wb').write(png)


def main():
    fb = read_xwd(sys.argv[1])
    mode = sys.argv[2]
    print('framebuffer %dx%d depth %d, masks %s' % (fb['w'], fb['h'], fb['depth'], [hex(m) for m in fb['masks']]))
    ok = True
    for q in range(4):
        x0, y0 = (q & 1) * fb['w'] // 2, (q >> 1) * fb['h'] // 2
        qw, qh = fb['w'] // 2, fb['h'] // 2
        pts = [(x0 + qw * fx // 4, y0 + qh * fy // 4) for fx in (1, 2, 3) for fy in (1, 2, 3)]
        got = [pixel(fb, x, y) for x, y in pts]
        centre = got[4]
        if mode in EXPECT:
            want = EXPECT[mode][q]
            good = all(all(abs(a - b) <= 8 for a, b in zip(g, want)) for g in got)
            ok &= good
            print('  %-12s centre %s expected %s: %s' % (NAMES[q], centre, want, 'OK' if good else 'MISMATCH %s' % got))
        else:
            print('  %-12s centre %s' % (NAMES[q], centre))
    if len(sys.argv) > 3:
        write_png(fb, sys.argv[3])
        print('screenshot: %s' % sys.argv[3])
    sys.exit(0 if ok or mode not in EXPECT else 1)


if __name__ == '__main__':
    main()
