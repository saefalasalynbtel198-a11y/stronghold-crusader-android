"""Checks what the Windows test program (shc-display-test.exe under Wine) received from the app's input path
(DisplayCli input): message order, button identity, coordinates, hold times (message time = X server event time),
double click, no stray clicks. Prints one 'check ...: PASS/FAIL' line per gesture; exit 1 if any fails.
Usage: python3 -I check-input-log.py <display-test.log>"""
import re
import sys

ev = []
polls = []
for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
    m = re.search(r"display-test: input (\S+) (\S+) t=(-?\d+)", line)
    if m:
        ev.append((m.group(1), m.group(2), int(m.group(3))))
    m = re.search(r"display-test: poll left (\w+) right (\w+) t=(\d+)", line)
    if m:
        polls.append(m.groups())

bad = 0


def check(name, ok, detail=""):
    global bad
    print("check %s: %s%s" % (name, "PASS" if ok else "FAIL", " (" + detail + ")" if detail else ""))
    if not ok:
        bad += 1


def find(msg, pos, start=0):
    for i in range(start, len(ev)):
        if ev[i][0] == msg and (pos is None or ev[i][1] == pos):
            return i
    return -1


def count(msg, pos):
    return sum(1 for e in ev if e[0] == msg and e[1] == pos)


print("received %d input messages, %d polled button changes" % (len(ev), len(polls)))
for e in ev:
    print("  %s %s t=%d" % e)

# instant click (0.4.0 style), for comparison only
i = find("WM_LBUTTONDOWN", "100,700")
j = find("WM_LBUTTONUP", "100,700", max(i, 0))
if i >= 0 and j >= 0:
    print("info: 0.4.0-style click at 100,700: button held %d ms as the program sees it" % (ev[j][2] - ev[i][2]))

# 1. tap: one press and one release at 700,500, held like a mouse click
i = find("WM_LBUTTONDOWN", "700,500")
j = find("WM_LBUTTONUP", "700,500", max(i, 0))
held = ev[j][2] - ev[i][2] if i >= 0 and j > i else -1
check("tap = one left click at 700,500 held >= 80 ms", i >= 0 and j > i and held >= 80
      and count("WM_LBUTTONDOWN", "700,500") == 1 and count("WM_LBUTTONUP", "700,500") == 1, "held %d ms" % held)

# 2. drag: press at the start, moves with the left button held, release at the end
i = find("WM_LBUTTONDOWN", "200,150")
k = find("WM_MOUSEMOVE+L", None, max(i, 0))
j = find("WM_LBUTTONUP", "320,260", max(i, 0))
check("drag = left press 200,150, moves with left held, release 320,260", i >= 0 and i < k < j)

# 3. long press: right click, no left click there
i = find("WM_RBUTTONDOWN", "640,400")
j = find("WM_RBUTTONUP", "640,400", max(i, 0))
held = ev[j][2] - ev[i][2] if i >= 0 and j > i else -1
check("long press = right click at 640,400 (no left click)", i >= 0 and j > i and held >= 80
      and count("WM_LBUTTONDOWN", "640,400") == 0, "held %d ms" % held)

# 4. right-button mode: right held while moving
i = find("WM_RBUTTONDOWN", "600,300")
k = find("WM_MOUSEMOVE+R", None, max(i, 0))
j = find("WM_RBUTTONUP", "700,360", max(i, 0))
check("right-button mode = right press 600,300, moves with right held, release 700,360", i >= 0 and i < k < j)

# 5. double tap: Windows reports a double click at the first tap's position
i = find("WM_LBUTTONDOWN", "512,384")
k = find("WM_LBUTTONDBLCLK", "512,384", max(i, 0))
j = find("WM_LBUTTONUP", "512,384", max(k, 0))
check("double tap = WM_LBUTTONDBLCLK at 512,384", i >= 0 and i < k < j)

# 6. cancelled drag: released
i = find("WM_LBUTTONDOWN", "300,600")
j = find("WM_LBUTTONUP", None, max(i, 0))
check("cancelled drag = left released", i >= 0 and j > i)

# 7. second finger: no click at 800,200
check("two-finger touch = no click", count("WM_LBUTTONDOWN", "800,200") == 0 and count("WM_RBUTTONDOWN", "800,200") == 0)

# every press released, in order, per button
state = {"L": 0, "R": 0}
order_ok = True
for msg, _, _ in ev:
    b = "L" if msg.startswith("WM_LBUTTON") else "R" if msg.startswith("WM_RBUTTON") else None
    if b is None:
        continue
    if msg.endswith("DOWN") or msg.endswith("DBLCLK"):
        order_ok &= state[b] == 0
        state[b] = 1
    elif msg.endswith("UP"):
        order_ok &= state[b] == 1
        state[b] = 0
check("every press followed by its release, no button left held", order_ok and state == {"L": 0, "R": 0})

# polled state (like a game reading the mouse once per frame): informational
for p in polls[:40]:
    print("  poll left %s right %s t=%s" % p)
sys.exit(1 if bad else 0)
