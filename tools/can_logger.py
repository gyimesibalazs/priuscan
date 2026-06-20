#!/usr/bin/env python3
"""
PriusCAN serial logger — timestamps every line the ESP emits (JSON + CAN dump),
so you can see exactly WHAT changes and WHEN the bus goes silent.

Use case: find a "power off / ignition off" signal on CAN.
  1. Connect the ESP to the PC by USB (it stays powered from USB, so it keeps
     listening to the bus even after the car's ignition is switched off).
  2. Run this with the dump command (default 'D1 0 6FF' = all broadcasts, deduped):
        python can_logger.py COM5
        python can_logger.py /dev/ttyACM0 --cmd "D1 0 7FF"
  3. With the car in ACC/IGN-ON (bus awake), let it log a few seconds, then
     switch the ignition OFF and KEEP LOGGING ~60 s. Optionally switch back ON.
  4. Ctrl-C to stop. The log file (timestamped) is written next to this script.

Each line is prefixed with: <ms-since-start> <wall-clock> .
The last changing frames before the bus goes quiet are the shutdown candidates;
the gap until silence is the supercap time budget.
"""
import sys, time, argparse, os

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("port", nargs="?", help="serial port (COMx or /dev/ttyACM0)")
    ap.add_argument("--cmd", default="D1 0 6FF", help="dump command to send (default: D1 0 6FF)")
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--list", action="store_true", help="list serial ports and exit")
    args = ap.parse_args()

    try:
        import serial
        from serial.tools import list_ports
    except ImportError:
        print("pyserial needed:  pip install pyserial"); sys.exit(1)

    if args.list or not args.port:
        for p in list_ports.comports():
            print(f"  {p.device}  {p.description}")
        if not args.port:
            print("\nGive a port, e.g.  python can_logger.py COM5"); return

    ser = serial.Serial(args.port, args.baud, timeout=1)
    time.sleep(0.5)
    ser.reset_input_buffer()
    ser.write((args.cmd + "\n").encode())          # enable the CAN dump
    print(f"sent: {args.cmd!r}  (dump on)")

    fname = time.strftime("priuscan-canlog-%Y%m%d-%H%M%S.txt")
    fpath = os.path.join(os.path.dirname(os.path.abspath(__file__)), fname)
    t0 = time.time()
    last_rx = t0
    print(f"logging to {fpath}  (Ctrl-C to stop)")
    try:
        with open(fpath, "w", encoding="utf-8") as f:
            while True:
                line = ser.readline()
                now = time.time()
                if not line:
                    # note silence gaps >2 s (bus may be going to sleep)
                    if now - last_rx > 2.0:
                        gap = f"# --- silence {now-last_rx:.1f}s ---"
                        stamp = f"{int((now-t0)*1000):8d} {time.strftime('%H:%M:%S')}"
                        print(stamp, gap); f.write(f"{stamp} {gap}\n"); f.flush()
                        last_rx = now
                    continue
                last_rx = now
                try: s = line.decode(errors="replace").rstrip("\r\n")
                except Exception: continue
                if not s: continue
                stamp = f"{int((now-t0)*1000):8d} {time.strftime('%H:%M:%S.')}{int((now%1)*1000):03d}"
                f.write(f"{stamp} {s}\n"); f.flush()
                print(stamp, s)
    except KeyboardInterrupt:
        pass
    finally:
        try: ser.write(b"D0\n")                     # dump off
        except Exception: pass
        ser.close()
        print(f"\nstopped. log: {fpath}")

if __name__ == "__main__":
    main()
