#!/data/data/com.termux/files/usr/bin/python
"""FR3K-owned Termux ADB identity. No reuse/disruption of the HUD pairing key."""
import argparse
import getpass
import ipaddress
import json
import os
import pathlib
import re
import subprocess
import sys

HOME = pathlib.Path.home()
CONFIG = HOME / ".config/fr3k/termux-adb.json"
ADB = os.environ.get("FR3K_ADB_BINARY", "/data/data/com.termux/files/usr/bin/adb")

def run(args, timeout=12, input_text=None):
    try:
        return subprocess.run(args, input=input_text, text=True, capture_output=True, timeout=timeout, check=False)
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError) as exc:
        class Error:
            returncode = 124
            stdout = ""
            stderr = type(exc).__name__
        return Error()

def safe_ip(value):
    try:
        ip = ipaddress.ip_address(value.strip())
        if ip.version != 4 or not (ip.is_loopback or ip.is_private):
            raise ValueError
        return str(ip)
    except ValueError:
        raise SystemExit("BLOCKED: use this phone's private Wi-Fi IPv4 address (or 127.0.0.1).")

def port(value):
    try:
        n = int(str(value).strip())
        if 1 <= n <= 65535:
            return n
    except (TypeError, ValueError):
        pass
    raise SystemExit("BLOCKED: port must be an integer between 1 and 65535.")

def own_ip():
    p = run(["/data/data/com.termux/files/usr/bin/termux-wifi-connectioninfo"], timeout=4)
    if p.returncode == 0:
        try:
            return safe_ip(json.loads(p.stdout).get("ip", ""))
        except (ValueError, SystemExit, json.JSONDecodeError):
            pass
    return "127.0.0.1"

def cfg():
    try:
        obj = json.loads(CONFIG.read_text())
        return {"host": safe_ip(obj["host"]), "connect_port": port(obj["connect_port"])}
    except (OSError, ValueError, KeyError, SystemExit, TypeError, json.JSONDecodeError):
        return None

def save(host, connect_port):
    CONFIG.parent.mkdir(parents=True, exist_ok=True)
    tmp = CONFIG.with_suffix(".tmp")
    tmp.write_text(json.dumps({"host": host, "connect_port": connect_port}) + "\n")
    os.chmod(tmp, 0o600)
    tmp.replace(CONFIG)

def uid_on(serial):
    p = run([ADB, "-s", serial, "shell", "id", "-u"], timeout=6)
    return p.returncode == 0 and p.stdout.strip() == "2000"

def devices():
    p = run([ADB, "devices", "-l"], timeout=7)
    result = []
    for line in p.stdout.splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device" and ":" in parts[0]:
            result.append(parts[0])
    return result

def status():
    present = devices()
    valid = [serial for serial in present if uid_on(serial)]
    config = cfg()
    print("TERMUX ADB:", "CONNECTED · Android shell UID 2000" if valid else "NOT CONNECTED")
    print("TERMUX ADB KEY:", "PRESENT" if (HOME / ".android/adbkey").is_file() else "NOT GENERATED")
    print("WIRELESS ENDPOINT:", f'{config["host"]}:{config["connect_port"]}' if config else "NOT SAVED")
    print("ACTIVE ADB DEVICES:", len(valid))
    if valid:
        print("VERIFIED SERIAL:", valid[0])
    print("HUD ADB: independent identity; no change performed")
    return bool(valid)

def connect(host=None, connect_port=None, persist=True):
    existing = cfg()
    host = safe_ip(host or (existing or {}).get("host") or own_ip())
    cp = port(connect_port or (existing or {}).get("connect_port"))
    serial = f"{host}:{cp}"
    p = run([ADB, "connect", serial], timeout=13)
    if not uid_on(serial):
        print("BLOCKED: ADB did not authorize Termux. Verify Wireless debugging, connection port and pairing.", file=sys.stderr)
        return False
    if persist:
        save(host, cp)
    print("CONNECTED AND VERIFIED: Termux ADB shell UID 2000")
    print("ENDPOINT:", serial)
    print("HUD pairing remains separate")
    return True

def pair(host, pairing_port, connect_port):
    host = safe_ip(host or own_ip())
    pp = port(pairing_port)
    cp = port(connect_port)
    code = getpass.getpass("Enter Android's NEW 6-digit pairing code for Termux: ").strip()
    if not re.fullmatch(r"\d{6}", code):
        print("BLOCKED: expected a fresh six-digit code", file=sys.stderr)
        return False
    result = run([ADB, "pair", f"{host}:{pp}"], timeout=25, input_text=code + "\n")
    output = (result.stdout + "\n" + result.stderr).replace(code, "[redacted]")
    del code
    if result.returncode != 0 or not re.search(r"successfully paired", output, re.I):
        print("BLOCKED: Termux ADB pairing was not accepted; check pairing port and regenerate code.", file=sys.stderr)
        return False
    print("TERMUX PAIRING ACCEPTED (not yet connected)")
    return connect(host, cp)

def developer_options():
    p = run(["/data/data/com.termux/files/usr/bin/am", "start",
             "-a", "android.settings.APPLICATION_DEVELOPMENT_SETTINGS"], timeout=7)
    print("ANDROID SETTINGS:", "OPEN REQUEST SENT" if p.returncode == 0 else "FAILED TO OPEN")
    return p.returncode == 0

def main():
    ap = argparse.ArgumentParser(description="Termux's separate FR3K wireless ADB identity; never alters HUD pairing.")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("status")
    sub.add_parser("settings")
    sub.add_parser("setup")
    pair_parser = sub.add_parser("pair")
    pair_parser.add_argument("--host")
    pair_parser.add_argument("--pair-port", required=True)
    pair_parser.add_argument("--connect-port", required=True)
    con = sub.add_parser("connect")
    con.add_argument("--host")
    con.add_argument("--connect-port")
    args = ap.parse_args()
    if args.cmd == "status":
        return 0 if status() else 2
    if args.cmd == "settings":
        return 0 if developer_options() else 3
    if args.cmd == "connect":
        try:
            return 0 if connect(args.host, args.connect_port) else 4
        except SystemExit:
            return 4
    if args.cmd == "pair":
        return 0 if pair(args.host, args.pair_port, args.connect_port) else 5
    print("FR3K TERMUX ADB · separate from already paired HUD")
    print("1) Leave HUD pairing alone.")
    print("2) In Android Developer options > Wireless debugging, select Pair device with pairing code.")
    print("3) Keep the pairing dialog visible (split screen if needed). Use its pairing port, NOT the connection port.")
    print("4) Return to the main Wireless debugging screen to identify the distinct connection port.")
    print("5) Input the two ports below. Code input will not echo or be saved.")
    print("Android Wi-Fi address:", own_ip())
    host = input("Wi-Fi IP (Enter for detected): ").strip() or own_ip()
    pairing_port = input("NEW Termux pairing port: ").strip()
    connect_port = input("Main Wireless debugging connection port: ").strip()
    return 0 if pair(host, pairing_port, connect_port) else 5

if __name__ == "__main__":
    sys.exit(main())
