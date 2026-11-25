"""stdin/stdout <-> Xvfb's abstract socket @/tmp/.X11-unix/X<display>, so the app's Java X11 client (core/X11Input)
can talk to the runtime's Xvfb in CI the way the app does on Android (LocalSocket, abstract namespace).
Usage: python3 -I x11-abstract-bridge.py <display>"""
import os
import socket
import sys
import threading

s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.connect("\0/tmp/.X11-unix/X" + sys.argv[1])


def upstream():
    while True:
        d = os.read(0, 65536)
        if not d:
            s.shutdown(socket.SHUT_WR)
            return
        s.sendall(d)


threading.Thread(target=upstream, daemon=True).start()
while True:
    d = s.recv(65536)
    if not d:
        break
    os.write(1, d)
