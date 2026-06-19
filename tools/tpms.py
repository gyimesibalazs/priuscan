#!/usr/bin/env python3
"""
TPMS USB vevo (CH340 @ 19200 8N1) dekoder + parancskuldo.

Protokoll:  55 AA | CMD | POS | D0 D1 D2 | XOR
  CMD 0x08 = szenzor adatkeret (broadcast)
  POS      = 0x00 FL, 0x01 FR, 0x10 RL, 0x11 RR, 0x05 potkerek
  D0       = nyomas:  kPa = D0 * 3.44   (bar = kPa/100)
  D1       = homerseklet: degC = D1 - 50
  D2       = flag-ek (0x20 = OK ezen az eszkozon; 0x00 elofordul)
  XOR      = XOR(55 .. D2)

Hasznalat:
  python3 tools/tpms.py monitor                 # elo dekodolas nevesitett kerekekkel
  python3 tools/tpms.py raw                      # nyers hex+frame dump
  python3 tools/tpms.py query                    # osszes szenzor-ID lekerese
  python3 tools/tpms.py pair FL|FR|RL|RR|SPARE   # parositas adott pozciora
  python3 tools/tpms.py pair-stop                # tanulo mod elhagyasa
  python3 tools/tpms.py send 55 AA 06 ...        # nyers parancs (XOR-t hozzateszi, ha hianyzik)
"""
import sys
import time

try:
    import serial
except ImportError:
    sys.exit("Hianyzik a pyserial:  pip install pyserial")

PORT = "/dev/ttyUSB0"
BAUD = 19200

POS_NAME = {0x00: "elso bal  (FL)", 0x01: "elso jobb (FR)",
            0x10: "hatso bal (RL)", 0x11: "hatso jobb(RR)",
            0x05: "potkerek  (SP)"}
PAIR_POS = {"FL": 0x00, "FR": 0x01, "RL": 0x10, "RR": 0x11, "SPARE": 0x05}


def xor(bs) -> int:
    x = 0
    for b in bs:
        x ^= b
    return x


def build(payload) -> bytes:
    """55 AA + payload + XOR. A payload a CMD-tol kezdodik (pl. [0x06,0x01,0x00,0x00])."""
    frame = bytes([0x55, 0xAA]) + bytes(payload)
    return frame + bytes([xor(frame)])


def open_port():
    try:
        return serial.Serial(PORT, BAUD, timeout=0.2)
    except serial.SerialException as e:
        sys.exit(f"Nem nyithato {PORT}: {e}\nJogok? -> sudo chmod a+rw {PORT}")


def frames(s):
    """Generator: reszinkronizal 55 AA-ra, validalja az XOR-t, kiad (cmd,pos,data,ok)."""
    buf = bytearray()
    while True:
        buf += s.read(256)
        while True:
            i = buf.find(b"\x55\xaa")
            if i < 0:
                if len(buf) > 1:
                    del buf[:-1]
                break
            if i:
                del buf[:i]
            if len(buf) < 8:           # ez az eszkoz 8 byte-os kereteket kuld
                break
            f = bytes(buf[:8])
            ok = xor(f[:-1]) == f[-1]
            yield f[2], f[3], f[4:7], ok
            del buf[:8]


def decode_pressure_bar(d0): return d0 * 3.44 / 100.0
def decode_temp_c(d1):       return d1 - 50


def monitor():
    print(f"TPMS monitor @ {BAUD} baud  (Ctrl-C kilepes)\n")
    latest = {}
    with open_port() as s:
        try:
            for cmd, pos, data, ok in frames(s):
                if cmd != 0x08 or not ok:
                    continue
                latest[pos] = data
                # egyszeru ujrarajzolas
                lines = []
                for p in (0x00, 0x01, 0x10, 0x11, 0x05):
                    if p in latest:
                        d = latest[p]
                        lines.append(f"  {POS_NAME[p]:<16}  "
                                     f"{decode_pressure_bar(d[0]):4.2f} bar   "
                                     f"{decode_temp_c(d[1]):3d} C   flag=0x{d[2]:02x}")
                sys.stdout.write("\033[H\033[J" + "TPMS elo adat\n\n" + "\n".join(lines) + "\n")
                sys.stdout.flush()
        except KeyboardInterrupt:
            print("\nKilepes.")


def raw():
    print(f"Nyers frame dump @ {BAUD} baud  (Ctrl-C kilepes)\n")
    with open_port() as s:
        try:
            for cmd, pos, data, ok in frames(s):
                tag = "OK " if ok else "XOR!"
                name = POS_NAME.get(pos, f"0x{pos:02x}")
                print(f"[{tag}] cmd=0x{cmd:02x} pos=0x{pos:02x} {name:<16} "
                      f"data={data.hex(' ')}")
        except KeyboardInterrupt:
            print("\nKilepes.")


def send_raw(frame: bytes, wait=2.0):
    with open_port() as s:
        s.reset_input_buffer()
        s.write(frame)
        s.flush()
        print(f"-> kuldve: {frame.hex(' ')}")
        end = time.time() + wait
        got = bytearray()
        while time.time() < end:
            got += s.read(256)
        if got:
            print(f"<- valasz ({len(got)} byte):")
            for off in range(0, len(got), 8):
                print("   " + got[off:off + 8].hex(' '))
        else:
            print("<- (nincs valasz)")


def main():
    if len(sys.argv) < 2:
        print(__doc__); return
    cmd = sys.argv[1].lower()
    if cmd == "monitor":
        monitor()
    elif cmd == "raw":
        raw()
    elif cmd == "query":
        send_raw(build([0x06, 0x07, 0x00, 0x00]))
    elif cmd == "pair":
        if len(sys.argv) < 3 or sys.argv[2].upper() not in PAIR_POS:
            sys.exit("hasznalat: pair FL|FR|RL|RR|SPARE")
        pos = PAIR_POS[sys.argv[2].upper()]
        print("Tanulo mod inditasa. Aktivald a szenzort (engedj/pumpalj a gumin).")
        send_raw(build([0x06, 0x01, pos, 0x00]), wait=5.0)
    elif cmd == "pair-stop":
        send_raw(build([0x06, 0x06, 0x00, 0x00]))
    elif cmd == "send":
        bs = [int(x, 16) for x in sys.argv[2:]]
        frame = bytes(bs)
        # ha nincs ra XOR a vegen, tegyuk hozza
        if len(frame) < 3 or xor(frame[:-1]) != frame[-1]:
            frame = frame + bytes([xor(frame)])
        send_raw(frame)
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
