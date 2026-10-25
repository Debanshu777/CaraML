#!/usr/bin/env python3
"""Export compact diffusion evidence without device identifiers or unrelated logs."""
import argparse
import json
from pathlib import Path
import re
from run_matrix import parse_thermal


def summarize(path):
    raw = json.loads(path.read_text())
    events = raw["events"]
    config = next((e for e in events if e.get("event") == "config"), {})
    load = next((e.get("ms") for e in events if e.get("event") == "load"), None)
    log_path = path.with_name("logcat.txt")
    log = log_path.read_text() if log_path.exists() else ""
    # Retain only this process's generation counts, using its matching load marker.
    starts = list(re.finditer(r"\s(\d+)\s+\d+\s+[ID]\s+DiffusionRunner: load_model: ENTER", log))
    pid = starts[-1].group(1) if starts else None
    backend_counts = []
    for line in log.splitlines():
        if "generation_graph_nodes " in line and (pid is None or re.search(rf"\s{pid}\s", line)):
            backend_counts.append({key: int(value) for key, value in
                                   re.findall(r"(cpu|vulkan|metal|cuda|other)=(\d+)", line)})
    generations = []
    for event in events:
        if event.get("event") != "generation":
            continue
        item = dict(event)
        progress = [p for p in event.get("progress", [])
                    if p["total"] == event["steps"] and 0 < p["step"] <= event["steps"]]
        if len(progress) > 1 and progress[-1]["step"] > progress[0]["step"]:
            item["sampling_interval_ms"] = ((progress[-1]["elapsed_ms"] - progress[0]["elapsed_ms"]) /
                                            (progress[-1]["step"] - progress[0]["step"]))
        if progress and progress[-1]["step"] == event["steps"]:
            item["after_last_step_ms"] = event["ms"] - progress[-1]["elapsed_ms"]
        generations.append(item)
    return {"tag": path.parent.name, "success": raw["success"], "config": config,
            "load_ms": load, "generations": generations, "scheduled_graph_nodes": backend_counts,
            "errors": [e for e in events if e.get("event") in ["error", "generation_failed"]],
            "process_exit": raw.get("process_exit"), "host_timeout": raw.get("host_timeout"),
            "apk_sha256": raw.get("apk_sha256", []), "git_base": raw.get("git_commit"),
            "started_utc": raw.get("started_utc"), "files": raw["files"],
            "thermal_conditions": {label: parse_thermal(value["thermal"]) for label, value in raw["conditions"].items()},
            "device_model": raw.get("device", {}).get("ro.product.model", "Pixel 9")}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=Path("build/diffusion-benchmark/results"))
    parser.add_argument("--output", type=Path, default=Path("benchmarks/diffusion/results/pixel9-2026-10-25.json"))
    args = parser.parse_args(argv)
    results = [summarize(p) for p in sorted(args.input.glob("*/result.json"))]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"trials": results}, indent=2) + "\n")
    print(f"Saved {len(results)} trials to {args.output}")


if __name__ == "__main__":
    main()
