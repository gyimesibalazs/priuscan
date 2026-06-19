#!/usr/bin/env python3
"""
TPMS USB (CH340) soros sniffer / baud-felderito.

Hasznalat:
  python3 tools/tpms_sniff.py                 # baud-sopres, majd hex-dump a legjobbon
  python3 tools/tpms_sniff.py --baud 9600     # fix baud, folyamatos hex+ASCII dump
  python3 tools/tpms_sniff.py --scan-only     # csak a baud-felmeres
  python3 tools/tpms_sniff.py --raw out.bin   # nyers byte-ok mentese fajlba is

A felderites azt meri, melyik baud-on a legkisebb a "framing zaj" aranya
(nyomtathato ASCII + jellemzo keret-byte-ok), es mennyi adat jon egyaltalan.
"""
import argparse
import sys
import time
import collections

try:
    import serial
except ImportError:
    sys.exit("Hianyzik a pyserial:  pip install pyserial")

PORT = "/dev/ttyUSB0"
COMMON_BAUDS = [9600, 19200, 38400, 57600, 115200, 250000, 4800, 2400]


def hexdump(buf: bytes, base: int = 0) -> None:
    """Klasszikus 16 byte/sor hex + ASCII dump."""
    for off in range(0, len(buf), 16):
        chunk = buf[off:off + 16]
        hexs = " ".join(f"{b:02x}" for b in chunk)
        asci = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
        print(f"{base + off:08x}  {hexs:<47}  |{asci}|")


def sample(port: str, baud: int, secs: float) -> bytes:
    try:
        with serial.Serial(port, baud, timeout=0.2) as s:
            time.sleep(0.05)
            s.reset_input_buffer()
            end = time.time() + secs
            out = bytearray()
            while time.time() < end:
                out += s.read(4096)
            return bytes(out)
    except serial.SerialException as e:
        sys.exit(f"Nem nyithato a port ({port}): {e}\n"
                 f"Jogok? ->  sudo chmod a+rw {port}")


def score(buf: bytes) -> float:
    """Heurisztika: minel tobb a printable/strukturalt byte es minel tobb az
    adat, annal magasabb. Csak relativ osszehasonlitasra jo a baud-ok kozt."""
    if not buf:
        return 0.0
    printable = sum(1 for b in buf if 32 <= b < 127 or b in (0x0a, 0x0d))
    freq = collections.Counter(buf)
    top = max(freq.values()) / len(buf)
    return (printable / len(buf)) * 0.7 + top * 0.3 + min(len(buf), 256) / 256 * 0.2


def scan(port: str) -> int:
    print("Baud-sopres (1.5 mp / sebesseg)...\n")
    results = []
    for b in COMMON_BAUDS:
        buf = sample(port, b, 1.5)
        sc = score(buf)
        results.append((sc, b, buf))
        preview = buf[:24].hex(" ") if buf else "(semmi)"
        print(f"  {b:>7} baud:  {len(buf):>5} byte  score={sc:.3f}  {preview}")
    results.sort(key=lambda r: r[0], reverse=True)
    best_sc, best_b, best_buf = results[0]
    print(f"\n>>> Legvaloszinubb baud: {best_b}  (score {best_sc:.3f})\n")
    print("Minta a legjobb baud-on:")
    hexdump(best_buf[:256])
    return best_b


def live(port: str, baud: int, raw_path) -> None:
    print(f"Folyamatos dump @ {baud} baud  (Ctrl-C a leallitashoz)\n")
    raw = open(raw_path, "ab") if raw_path else None
    total = 0
    with serial.Serial(port, baud, timeout=0.2) as s:
        s.reset_input_buffer()
        try:
            while True:
                buf = s.read(4096)
                if not buf:
                    continue
                if raw:
                    raw.write(buf); raw.flush()
                hexdump(buf, total)
                total += len(buf)
        except KeyboardInterrupt:
            print(f"\nLeallitva. Osszesen {total} byte.")
        finally:
            if raw:
                raw.close()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", default=PORT)
    ap.add_argument("--baud", type=int, help="fix baud; ha nincs, eloszor sopres")
    ap.add_argument("--scan-only", action="store_true")
    ap.add_argument("--raw", help="nyers byte-ok mentese ebbe a fajlba is")
    args = ap.parse_args()

    if args.baud and not args.scan_only:
        live(args.port, args.baud, args.raw)
        return

    best = scan(args.port)
    if args.scan_only:
        return
    print()
    live(args.port, best, args.raw)


if __name__ == "__main__":
    main()
