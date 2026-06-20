#!/usr/bin/env python3
"""
PriusCAN serial-OTA tester (PC side) — exactly mirrors the Android app's doSerialOta,
so you can test/debug the firmware OTA receiver from Windows (where the ESP USB port is)
WITHOUT the head-unit app. Fast iteration loop.

Protocol (app/tool <-> ESP):
    -> "O<size>\n"            (size = raw image bytes)
    <- "K\n"                  (ready, inactive partition erased)
    -> "<base64-of-600-raw-bytes>\n"   (per chunk)
    <- "A\n"                  (ack -> send next)
    ... repeat ...
    <- "D\n"                  (done -> ESP reboots)   or "E\n" (error)

Usage (Windows):
    pip install pyserial
    python serial_ota.py COM7 path\\to\\firmware.bin
    # list ports:  python serial_ota.py --list

The firmware.bin is the ESPHome app image:
    .esphome/build/prius-can/.pioenvs/prius-can/firmware.bin
"""
import sys, time, base64, argparse

try:
    import serial
    from serial.tools import list_ports
except ImportError:
    sys.exit("pyserial kell:  pip install pyserial")

RAW_CHUNK = 600          # raw bytes per base64 line (matches the app)
BAUD = 115200            # ignored on native USB-CDC, but harmless


def list_serial_ports():
    ports = list(list_ports.comports())
    if not ports:
        print("Nincs soros port.")
    for p in ports:
        print(f"  {p.device:10}  {p.description}")


def read_line(ser, timeout):
    """Read one '\\n'-terminated line (returns the text without newline), or None on timeout."""
    deadline = time.time() + timeout
    buf = bytearray()
    while time.time() < deadline:
        b = ser.read(1)
        if not b:
            continue
        if b == b"\n":
            return buf.decode("ascii", "replace").rstrip("\r")
        buf += b
        if len(buf) > 4096:
            buf.clear()
    return None


def expect(ser, want, timeout, verbose):
    """Read lines until one starts with `want` (return True) or 'E' / timeout (False)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        line = read_line(ser, max(0.1, deadline - time.time()))
        if line is None:
            continue
        if verbose and line:
            print(f"   rx<- {line[:80]}")
        if line[:1] == want:
            return True
        if line[:1] == "E":
            print("   !! ESP error (E)")
            return False
    print(f"   !! timeout waiting for '{want}'")
    return False


def main():
    ap = argparse.ArgumentParser(description="PriusCAN serial-OTA tester")
    ap.add_argument("port", nargs="?", help="serial port, e.g. COM7 or /dev/ttyACM0")
    ap.add_argument("image", nargs="?", help="firmware.bin (ESPHome app image)")
    ap.add_argument("--list", action="store_true", help="list serial ports")
    ap.add_argument("-q", "--quiet", action="store_true", help="less verbose")
    args = ap.parse_args()

    if args.list or not (args.port and args.image):
        list_serial_ports()
        if not (args.port and args.image):
            sys.exit("\nHasznalat: python serial_ota.py <PORT> <firmware.bin>")
        return

    verbose = not args.quiet
    with open(args.image, "rb") as f:
        img = f.read()
    print(f"image: {args.image}  ({len(img)} byte)")

    ser = serial.Serial(args.port, BAUD, timeout=0.1)
    time.sleep(0.3)
    ser.reset_input_buffer()

    # 1) start OTA
    print(f"-> O{len(img)}")
    ser.write(f"O{len(img)}\n".encode())
    if not expect(ser, "K", 8.0, verbose):
        sys.exit("FAIL: nincs READY (K). Fut-e a serial-OTA-kepes firmware?")
    print("READY (K) - partition erased, streaming...")

    # 2) stream base64 lines, wait for an ack per chunk
    t0 = time.time()
    off = 0
    last_pct = -1
    while off < len(img):
        end = min(off + RAW_CHUNK, len(img))
        line = base64.b64encode(img[off:end]).decode("ascii")
        ser.write((line + "\n").encode())
        if not expect(ser, "A", 10.0, False):
            sys.exit(f"FAIL: nincs ACK (A) @ offset {off} ({off*100//len(img)}%)")
        off = end
        pct = off * 100 // len(img)
        if pct != last_pct:
            print(f"\r  {pct:3d}%  ({off}/{len(img)})", end="", flush=True)
            last_pct = pct
    print()

    # 3) done
    if not expect(ser, "D", 12.0, verbose):
        sys.exit("FAIL: nincs DONE (D) a vegen")
    dt = time.time() - t0
    print(f"OK: firmware streamelve {dt:.1f}s alatt ({len(img)/dt/1024:.1f} kB/s). Az ESP ujraindul.")
    ser.close()


if __name__ == "__main__":
    main()
