#!/usr/bin/env python3
"""Run a serial Android trial matrix, cooling between cases and retaining failures."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import time


def parse_thermal(text):
    match = re.search(r"Thermal Status:\s*(\d+)", text)
    if not match:
        raise RuntimeError("Cannot read device thermal status")
    current = text.partition("Current temperatures from HAL:")[2].partition("Current cooling devices from HAL:")[0]
    temperatures = {name: float(value) for value, name in
                    re.findall(r"Temperature\{mValue=([\d.]+), mType=-?\d+, mName=([^,]+), mStatus=\d+\}", current)}
    cooling_section = text.partition("Current cooling devices from HAL:")[2].partition("Temperature static thresholds")[0]
    cooling = {name: int(value) for value, kind, name in
               re.findall(r"CoolingDevice\{mValue=(\d+), mType=(\d+), mName=([^}]+)\}", cooling_section)
               if int(kind) in [2, 3]}
    cpu = max((temperatures[name] for name in ["LITTLE", "MID", "BIG"] if name in temperatures), default=None)
    gpu = temperatures.get("G3D")
    skin = temperatures.get("VIRTUAL-SKIN")
    status = int(match.group(1))
    # Pixel's API can stay at LIGHT while USB/skin sensors remain warm, even
    # with zero CPU/GPU cooling. Use live HAL readings, not the cached section.
    ready = (status <= 1 and cpu is not None and cpu <= 45 and gpu is not None and gpu <= 45
             and skin is not None and skin <= 38 and bool(cooling) and not any(cooling.values()))
    return {"status": status, "temperatures_c": temperatures, "compute_cooling": cooling, "ready": ready}


def thermal_state():
    result = subprocess.run(["adb", "shell", "dumpsys", "thermalservice"],
                            capture_output=True, text=True, check=True)
    return parse_thermal(result.stdout)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("matrix", type=Path, nargs="?", default=Path(__file__).with_name("android-suites.json"),
                        help="Named suites JSON or a custom JSON array of run_android.py argument arrays")
    parser.add_argument("--suite", help="Named suite to run (default: baseline; bundled: baseline, tuning, smoke)")
    parser.add_argument("--cooldown", type=int, default=180, help="Maximum seconds to await cool CPU/GPU readings and no compute cooling")
    parser.add_argument("--screen-off-during-cooldown", action="store_true")
    args = parser.parse_args(argv)
    trials = json.loads(args.matrix.read_text())
    if isinstance(trials, dict):
        suite = args.suite or "baseline"
        if suite not in trials:
            parser.error(f"Unknown suite {suite!r}; choose from: {', '.join(trials)}")
        trials = trials[suite]
    elif args.suite is not None:
        parser.error("--suite requires a named-suites JSON file")
    if not isinstance(trials, list):
        parser.error("The selected matrix must be an array of trial argument arrays")
    for trial in trials:
        tag = trial[trial.index("--tag") + 1]
        existing = Path("build/diffusion-benchmark/results") / tag / "result.json"
        if existing.exists():
            print(json.dumps({"tag": tag, "existing": True}), flush=True)
            continue
        if args.screen_off_during_cooldown:
            subprocess.run(["adb", "shell", "input", "keyevent", "KEYCODE_SLEEP"], check=True)
        deadline = time.monotonic() + args.cooldown
        state = thermal_state()
        while not state["ready"] and time.monotonic() < deadline:
            print(json.dumps({"tag": tag, "cooling_thermal_status": state["status"]}), flush=True)
            time.sleep(15)
            state = thermal_state()
        print(json.dumps({"tag": tag, "starting_thermal_state": state}), flush=True)
        # A warm start is retained and labelled in each result, never called controlled.
        result = subprocess.run([sys.executable, str(Path(__file__).with_name("run_android.py")), *trial])
        print(json.dumps({"tag": tag, "exit_code": result.returncode}), flush=True)


if __name__ == "__main__":
    main()
