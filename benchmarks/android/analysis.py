"""Trace quality, archived UI analysis and Vulkan timing summaries."""
import csv
import io
import json
import math
from pathlib import Path
import re
import subprocess
import argparse
import statistics
from collections import defaultdict


QUALITY_SQL = Path(__file__).parent / "analysis" / "quality-gate.sql"


def query_json(processor, query, trace):
    result = subprocess.run([str(processor), "query", "-f", str(query), str(trace)],
                            capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or f"trace_processor exited {result.returncode}")
    output = result.stdout.strip()
    # Older/current trace_processor CSV output does not consistently escape quotes
    # within json_object(). Accept both valid CSV and its single quoted JSON cell.
    try:
        row = next(csv.DictReader(io.StringIO(output)))
        return json.loads(row["result"])
    except (ValueError, KeyError, TypeError, StopIteration):
        cell = output.split("\n", 1)[1].strip()
        return json.loads(cell[1:-1] if cell.startswith('"') and cell.endswith('"') else cell)


def inspect_trace(processor, trace, expected_refresh_hz=None, refresh_validation=None, stream_period_ms=None):
    record = {"file": str(trace), "bytes": trace.stat().st_size, "valid": False, "reasons": []}
    if not record["bytes"]:
        record["reasons"].append("Empty trace")
        return record
    try:
        record.update(query_json(processor, QUALITY_SQL, trace))
    except (OSError, ValueError, IndexError, RuntimeError) as error:
        record["reasons"].append(f"Trace analysis failed: {error}")
        return record
    if record["errors"]:
        record["reasons"].append("Nonzero Perfetto error/data_loss statistics")
    if not record.get("durationMs") or record["durationMs"] <= 0:
        record["reasons"].append("No positive trace duration")
    is_scroll = "ScrollBenchmark_" in trace.name
    if is_scroll:
        if not record.get("measurementWindowMs"):
            record["reasons"].append("Missing measured scrolling window")
        if record["frameCount"] < 10:
            record["reasons"].append("Fewer than 10 measured FrameTimeline deadlines")
        if "streamingFling" in trace.name:
            period = stream_period_ms or 100
            window = record.get("measurementWindowMs") or 0
            minimum_updates = max(1, math.floor(window / period * 0.5))
            minimum_span = max(0, window - 2 * period) * 0.5
            record["streamValidation"] = {"periodMs": period, "minimumUpdates": minimum_updates,
                                          "minimumActiveSpanMs": minimum_span}
            if (record.get("streamUpdateCount", 0) < minimum_updates
                    or (record.get("streamUpdateSpanMs") or 0) < minimum_span):
                record["reasons"].append("Streaming was not sustained across the measured window")
    # Expected duration is an app completion budget, often spanning multiple
    # display periods. Callback cadence and submitted-frame cadence also differ.
    # Only explicit display-mode metadata establishes the requested endpoint mode.
    if expected_refresh_hz is not None and is_scroll:
        if not refresh_validation or not refresh_validation["valid"]:
            record["reasons"].append("Requested refresh rate was not verified by active display-mode endpoints")
    record["valid"] = not record["reasons"]
    return record


def display_mode_endpoints(archive, requested_hz):
    root = archive if (archive / "device-before.json").is_file() else archive.parent
    result = {"method": "primary display active-mode endpoints; not continuous panel validation",
              "requestedHz": requested_hz, "valid": False, "endpoints": {}, "reasons": []}
    for endpoint in ("before", "after"):
        try:
            snapshot = json.loads((root / f"device-{endpoint}.json").read_text())
            modes = re.findall(r"mActiveSfDisplayMode=DisplayMode\{[^\n}]*peakRefreshRate=([\d.]+),\s*vsyncRate=([\d.]+)", snapshot["display"])
            if len(modes) != 1:
                raise ValueError("Expected one unambiguous active physical display mode")
            peak, vsync = map(float, modes[0])
            result["endpoints"][endpoint] = {"activeModePeakRefreshHz": peak, "modeVsyncHz": vsync}
            if abs(peak / requested_hz - 1) > 0.02:
                result["reasons"].append(f"{endpoint} active display mode {peak:g} Hz differs from requested {requested_hz:g} Hz")
        except (OSError, ValueError, KeyError) as error:
            result["reasons"].append(f"Cannot verify {endpoint} display mode: {error}")
    result["valid"] = not result["reasons"]
    return result


def inspect_archive(archive, processor, expected_count=None, expected_refresh_hz=None, stream_period_ms=None):
    traces = sorted(archive.rglob("*.perfetto-trace"))
    reasons = []
    root = archive if (archive / "run.json").is_file() else archive.parent
    metadata_path = root / "run.json"
    if stream_period_ms is None and metadata_path.is_file():
        try:
            stream_period_ms = json.loads(metadata_path.read_text()).get("streamPeriodMs", 100)
            if not isinstance(stream_period_ms, int) or not 25 <= stream_period_ms <= 1000:
                raise ValueError("Invalid archived streamPeriodMs")
        except (OSError, ValueError) as error:
            reasons.append(f"Cannot verify archived streaming configuration: {error}")
            stream_period_ms = 100
    refresh = display_mode_endpoints(archive, expected_refresh_hz) if expected_refresh_hz else None
    entries = [inspect_trace(processor, trace, expected_refresh_hz, refresh, stream_period_ms) for trace in traces]
    for entry in entries:
        entry["file"] = str(Path(entry["file"]).relative_to(archive))
    if not entries:
        reasons.append("No Perfetto traces found")
    if expected_count is not None and len(entries) != expected_count:
        reasons.append(f"Expected {expected_count} traces, found {len(entries)}")
    if any(not entry["valid"] for entry in entries):
        reasons.append("One or more traces failed quality/refresh validation")
    return {"valid": not reasons, "reasons": reasons, "expectedTraceCount": expected_count,
            "expectedRefreshHz": expected_refresh_hz, "traceCount": len(entries),
            "refreshValidation": refresh,
            "invalidTraceCount": sum(not entry["valid"] for entry in entries), "traces": entries}


def traces_main(argv=None):
    """Summarize archived Macrobenchmark traces using the official Perfetto trace processor."""
    parser = argparse.ArgumentParser(description=traces_main.__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--trace-processor", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--expected-refresh-hz", type=float, choices=[60, 90, 120])
    parser.add_argument("--allow-invalid", action="store_true", help="Inspect bad historical traces; excluded from summary")
    args = parser.parse_args(argv)
    traces = sorted(args.archive.rglob("*.perfetto-trace"))
    if not traces:
        parser.error("No Perfetto traces found in archive")
    processor = args.trace_processor.resolve()
    quality = inspect_archive(args.archive, processor, expected_refresh_hz=args.expected_refresh_hz)
    quality_by_file = {entry["file"]: entry for entry in quality["traces"]}
    report = {
        "archive": str(args.archive.resolve()),
        "traceProcessorVersion": subprocess.check_output([str(processor), "--version"], text=True).strip(),
        "notes": [
            "Inclusive slice times overlap; do not add them to derive TTID.",
            "FrameTimeline jank classification differs from Macrobenchmark's frameOverrunMs.",
            "Thermal validity comes from run.json; these queries cannot establish a controlled comparison.",
        ],
        "traces": [], "summary": {},
        "quality": quality,
    }
    groups = {}
    for trace in traces:
        scenario = trace.name.split("_iter", 1)[0]
        kind = "startup" if "StartupBenchmark_" in scenario else "scroll"
        if "StartupBenchmark_" not in scenario and "ScrollBenchmark_" not in scenario:
            continue
        trace_quality = quality_by_file[str(trace.relative_to(args.archive))]
        if not trace_quality["valid"]:
            report["traces"].append({"file": str(trace.relative_to(args.archive)), "metrics": {},
                                     "excluded": trace_quality["reasons"]})
            continue
        query = Path(__file__).parent / "analysis" / f"{kind}.sql"
        result = subprocess.run(
            [str(processor), "query", "-f", str(query), str(trace)],
            capture_output=True, text=True,
        )
        if result.returncode:
            raise RuntimeError(f"Could not analyze {trace}:\n{result.stderr}")
        metrics = {}
        for row in csv.DictReader(io.StringIO(result.stdout.strip())):
            metric = row.pop("metric")
            metrics[metric] = {"count": int(row["count"]), "total_ms": float(row["total_ms"]),
                               "max_ms": float(row["max_ms"])}
            groups.setdefault(scenario, {}).setdefault(metric, []).append(metrics[metric])
        report["traces"].append({"file": str(trace.relative_to(args.archive)), "metrics": metrics})
    for scenario, metrics in groups.items():
        report["summary"][scenario] = {
            name: {
                "tracesWithMetric": len(values),
                "countTotal": sum(v["count"] for v in values),
                "totalMsMedian": statistics.median(v["total_ms"] for v in values),
                "totalMsMin": min(v["total_ms"] for v in values),
                "totalMsMax": max(v["total_ms"] for v in values),
            } for name, values in metrics.items()
        }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"Analyzed {len(report['traces'])} traces → {args.output}")
    if not quality["valid"] and not args.allow_invalid:
        raise SystemExit("Trace quality failed; report saved. Invalid traces are excluded from summaries.")


NUMBER = r"(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?"


OP = re.compile(rf"^(.+): (\d+) x ({NUMBER}) us = ({NUMBER}) us(?: \(({NUMBER}) GFLOPS/s\))?$")


TOTAL = re.compile(rf"^Total time: ({NUMBER}) us\.$")


def summarize_vulkan(text):
    graphs = []
    current = None
    for line in text.splitlines():
        line = line.strip()
        if line == "Vulkan Timings:":
            if current is not None:
                raise ValueError("A GPU timing group did not finish before the next group")
            current = []
        elif current is not None:
            operation = OP.fullmatch(line)
            total = TOTAL.fullmatch(line)
            if operation:
                name, count, average, duration, flops = operation.groups()
                current.append({"operation": name, "count": int(count), "totalUs": float(duration),
                                "averageUs": float(average), "gflopsPerSecond": float(flops) if flops else None})
            elif total:
                duration = float(total[1])
                if not current or duration <= 0:
                    raise ValueError("GPU timing group has no operations or nonpositive duration")
                # The native logger rounds each printed value. A large mismatch
                # indicates an incomplete/interleaved log, not another GPU cost.
                summed = sum(item["totalUs"] for item in current)
                if abs(summed - duration) > max(1.0, duration * 0.001):
                    raise ValueError("GPU timing operation sum differs from reported total")
                graphs.append({"index": len(graphs) + 1, "totalUs": duration,
                               "operations": sorted(current, key=lambda item: item["totalUs"], reverse=True)})
                current = None
    if current is not None or not graphs:
        raise ValueError("No complete GPU timing groups, or final group is truncated")
    aggregate = defaultdict(lambda: {"count": 0, "totalUs": 0.0})
    for graph in graphs:
        for operation in graph["operations"]:
            entry = aggregate[operation["operation"]]
            entry["count"] += operation["count"]
            entry["totalUs"] += operation["totalUs"]
    ranked = [{"operation": name, **value} for name, value in aggregate.items()]
    ranked.sort(key=lambda item: item["totalUs"], reverse=True)
    return {"diagnosticOnly": True, "groups": len(graphs), "totalGpuUs": sum(graph["totalUs"] for graph in graphs),
            "operations": ranked, "graphs": graphs,
            "interpretation": "GPU operation/fusion timings include diagnostic synchronization; they are not application latency or untraced throughput."}


def vulkan_main(argv=None):
    """Summarize GGML_VK_PERF_LOGGER output; GPU diagnostic durations are not TTFT."""
    parser = argparse.ArgumentParser(description=vulkan_main.__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    result = summarize_vulkan(args.log.read_text())
    output = args.output or args.log.with_suffix(".summary.json")
    output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({key: value for key, value in result.items() if key != "graphs"}, indent=2))
