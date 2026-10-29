"""Switch off the TV maker's telemetry and promotions, and put them back.

Usage (TV_IP=<tv address> .venv/bin/python scripts/debloat.py <command> [list]):
  plan     what would change, read only (the default)
  apply    switch the listed packages off (pm disable-user); nothing is uninstalled
  undo     switch back on exactly what `apply` switched off on this TV
  check    after a firmware update: which ones came back on (`check --fix` switches them off again)

The list defaults to on-tv/debloat/philips-tpm191e.txt. `apply` refuses a TV whose firmware is not
the one the list was tested on, unless FORCE=1. It counts the TV's inputs before and after, and puts
everything back by itself if any went missing. What it switched off is kept on this computer, in
~/.couch-tv/debloat-<tv>.json, so `undo` never touches packages that were already off from the factory.

Talks to the TV with tv-home/tools/tvadb.py (pure Python ADB, the same key as the rest of the repo).
Idea and list from CleanYourTV (MIT, github.com/Gathin23/cleanyourtv).
"""
import json
import os
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tv-home", "tools"))
import tvadb  # noqa: E402

LIST = os.path.join(ROOT, "on-tv", "debloat", "philips-tpm191e.txt")
PLATFORM = "PH9M_EA_5599"   # the firmware family the default list was tested on


def read_list(path):
    out = []
    for line in open(path, encoding="utf-8"):
        pkg = line.split("#", 1)[0].strip()
        if pkg:
            out.append(pkg)
    return out


def packages(tv, flag=""):
    return {l.split(":", 1)[1].strip() for l in tv.shell(f"pm list packages {flag}").splitlines() if l.startswith("package:")}


def inputs(tv):
    """How many TV inputs Android knows (HDMI ports, the tuner, devices behind ports)."""
    return tv.shell("dumpsys tv_input").count("TvInputInfo{")


def state_path(serial):
    d = os.path.expanduser("~/.couch-tv")
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, f"debloat-{serial}.json")


def load_state(path):
    try:
        return json.load(open(path))
    except (OSError, ValueError):
        return {"off": []}


def save_state(path, st):
    tmp = path + ".tmp"
    json.dump(st, open(tmp, "w"), indent=2)
    os.replace(tmp, path)


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "plan"
    fix = "--fix" in sys.argv[2:]
    args = [a for a in sys.argv[2:] if not a.startswith("--")]
    if cmd not in ("plan", "apply", "undo", "check"):
        sys.exit(__doc__)
    wanted = read_list(args[0] if args else LIST)

    try:
        tv = tvadb.Tv(tvadb.connect)
    except ConnectionError as e:
        sys.exit(f"debloat: {e}")
    fingerprint = tv.shell("getprop ro.build.fingerprint").strip()
    serial = tv.shell("getprop ro.serialno").strip() or os.environ.get("TV_IP", "tv")
    path = state_path(serial)
    st = load_state(path)
    print(f"TV: {fingerprint}")

    installed, on = packages(tv), packages(tv, "-e")

    if cmd == "plan":
        for p in wanted:
            print(("  switch off   " if p in on else "  already off  " if p in installed else "  not on TV    ") + p)
        if PLATFORM not in fingerprint:
            print(f"\nThis TV is not a {PLATFORM} set: `apply` will refuse unless FORCE=1.")
        return

    if cmd == "apply":
        if PLATFORM not in fingerprint and os.environ.get("FORCE") != "1":
            sys.exit(f"debloat: this list was tested on {PLATFORM} firmware, and this TV is not one. FORCE=1 to go ahead anyway.")
        before = inputs(tv)
        done = []
        for p in wanted:
            if p not in on:
                print(f"  skip  {p} ({'already off' if p in installed else 'not on this TV'})")
                continue
            out = tv.shell(f"pm disable-user --user 0 {p}").strip()
            if "disabled" not in out:
                print(f"  FAIL  {p}: {out}")
                continue
            done.append(p)
            st["off"] = sorted(set(st["off"]) | {p})
            st["fingerprint"] = fingerprint
            save_state(path, st)                 # after every one, so an interrupted run can still be undone
            print(f"  off   {p}")
        time.sleep(3)
        after = inputs(tv)
        if after < before:
            print(f"\nThe TV lists {after} inputs now, {before} before. Putting this run back.")
            for p in done:
                tv.shell(f"pm enable {p}")
            st["off"] = sorted(set(st["off"]) - set(done))
            save_state(path, st)
            sys.exit(1)
        print(f"\n{len(done)} switched off, inputs still {after}. Record: {path}")
        print("Check Live TV, SOURCES, Ambilight and casting with the remote. `undo` puts everything back.")
        return

    if cmd == "undo":
        if not st["off"]:
            print("Nothing recorded for this TV: nothing to put back.")
            return
        for p in list(st["off"]):
            out = tv.shell(f"pm enable {p}").strip()
            print(f"  on    {p}" if "enabled" in out else f"  FAIL  {p}: {out}")
            if "enabled" in out:
                st["off"].remove(p)
                save_state(path, st)
        return

    # check: firmware updates can switch packages back on
    back = [p for p in st["off"] if p in on]
    if not back:
        print(f"All {len(st['off'])} are still off.")
        return
    for p in back:
        if fix:
            tv.shell(f"pm disable-user --user 0 {p}")
        print(f"  {'off again' if fix else 'back on'}  {p}")
    if not fix:
        print("`check --fix` switches them off again.")


if __name__ == "__main__":
    main()
