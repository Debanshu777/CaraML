"""Perfetto and Simpleperf diagnostics, with shared workload completion checks."""
import json
from pathlib import Path
import re
import shlex
import argparse
from datetime import datetime, timezone
import subprocess
import time
from collections import Counter
import os
import signal
import sys


def instrumentation_arguments(command):
    words = list(command)
    if words and Path(words[0]).name == "adb" and "shell" in words:
        words = words[words.index("shell") + 1:]
        if len(words) == 1:
            words = shlex.split(words[0])
    if words[:2] == ["am", "instrument"]:
        return words[2:]
    return None


def require_raw_instrumentation(command):
    arguments = instrumentation_arguments(command)
    if arguments is not None and not {"-w", "-r"}.issubset(arguments):
        raise ValueError("am instrument workloads require -w -r for verifiable completion")


def validate_workload(command, exit_code, output):
    failures = []
    if exit_code != 0:
        failures.append(f"workload exited with {exit_code}")
    arguments = instrumentation_arguments(command)
    if arguments is not None:
        try:
            require_raw_instrumentation(command)
        except ValueError as error:
            failures.append(str(error))
        codes = re.findall(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", output, re.MULTILINE)
        if codes != ["-1"]:
            failures.append("instrumentation did not finish with exactly one success code (-1)")
        if re.search(r"^INSTRUMENTATION_(?:FAILED|ABORTED)|^INSTRUMENTATION_STATUS_CODE:\s*-[12]\s*$|FAILURES!!!", output, re.MULTILINE):
            failures.append("instrumentation reported a failure")
        records = []
        for line in output.splitlines():
            start = line.find("{")
            if start >= 0:
                try:
                    records.append(json.loads(line[start:]))
                except json.JSONDecodeError:
                    pass
        outcomes = [record for record in records if isinstance(record, dict) and record.get("stage") == "outcome"]
        if any(isinstance(record, dict) and record.get("stage") == "failure" for record in records):
            failures.append("harness reported a failure stage")
        if outcomes:
            if any(record.get("result") != "PASS" for record in outcomes):
                failures.append("harness did not pass every case")
        elif not re.search(r"\bOK \([1-9][0-9]* tests?\)", output):
            failures.append("no explicit passing harness outcomes or JUnit tests")
    return {"valid": not failures, "instrumentation": arguments is not None, "failures": failures}


ROOT = Path(__file__).resolve().parents[2]


def proto_fields(data):
    """Read protobuf wire fields needed by TracingServiceState, ignoring unknowns."""
    cursor = 0

    def varint():
        nonlocal cursor
        value = 0
        for shift in range(0, 70, 7):
            if cursor >= len(data):
                raise ValueError("Truncated protobuf varint")
            byte = data[cursor]
            cursor += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value
        raise ValueError("Invalid protobuf varint")

    while cursor < len(data):
        tag = varint()
        field, wire = tag >> 3, tag & 7
        if not field:
            raise ValueError("Invalid protobuf field")
        if wire == 0:
            value = varint()
        elif wire in (1, 2, 5):
            size = varint() if wire == 2 else (8 if wire == 1 else 4)
            if cursor + size > len(data):
                raise ValueError("Truncated protobuf field")
            value = data[cursor:cursor + size]
            cursor += size
        else:
            raise ValueError(f"Unsupported protobuf wire type {wire}")
        yield field, wire, value


def data_source_names(query):
    # TracingServiceState.data_sources=2, DataSource.ds_descriptor=1,
    # DataSourceDescriptor.name=1. Use --query-raw, not the human table layout.
    names = set()
    for field, wire, source in proto_fields(query):
        if field == 2 and wire == 2:
            for descriptor_field, descriptor_wire, descriptor in proto_fields(source):
                if descriptor_field == 1 and descriptor_wire == 2:
                    for name_field, name_wire, name in proto_fields(descriptor):
                        if name_field == 1 and name_wire == 2:
                            names.add(name.decode("utf-8"))
    return names


def make_config(package, sources, seconds, frequency, cpu_sampling=True, gpu_counter_ids=(),
                native_only=False):
    required = {"linux.ftrace", "linux.process_stats", "linux.sys_stats"}
    if cpu_sampling:
        required.add("linux.perf")
    missing = required - sources
    if missing:
        raise ValueError(f"Required sources unavailable: {sorted(missing)}")
    categories = () if native_only else ("am", "wm", "gfx", "view", "dalvik")
    atrace_categories = "".join(f'    atrace_categories: "{category}"\n' for category in categories)
    chunks = [f'''buffers {{ size_kb: 65536 fill_policy: RING_BUFFER }}
buffers {{ size_kb: 32768 fill_policy: RING_BUFFER }}
buffers {{ size_kb: 8192 fill_policy: RING_BUFFER }}
duration_ms: {seconds * 1000}
write_into_file: true
file_write_period_ms: 1000
flush_period_ms: 1000
incremental_state_config {{ clear_period_ms: 5000 }}
data_sources {{ config {{
  name: "linux.ftrace"
  target_buffer: 0
  ftrace_config {{
    buffer_size_kb: 4096
    drain_period_ms: 100
    ftrace_events: "sched/sched_switch"
    ftrace_events: "sched/sched_waking"
    ftrace_events: "sched/sched_process_exit"
    ftrace_events: "sched/sched_blocked_reason"
    ftrace_events: "power/cpu_frequency"
    ftrace_events: "power/cpu_idle"
    ftrace_events: "binder/binder_transaction"
    ftrace_events: "binder/binder_transaction_received"
{atrace_categories}    atrace_apps: "{package}"
  }}
}} }}
data_sources {{ config {{
  name: "linux.process_stats"
  target_buffer: 2
  process_stats_config {{
    scan_all_processes_on_start: true
    proc_stats_poll_ms: 250
  }}
}} }}
data_sources {{ config {{
  name: "linux.sys_stats"
  target_buffer: 2
  sys_stats_config {{
    meminfo_period_ms: 1000
    vmstat_period_ms: 1000
    cpufreq_period_ms: 1000
    devfreq_period_ms: 1000
  }}
}} }}''']
    if cpu_sampling:
        chunks.append(f'''data_sources {{ config {{
  name: "linux.perf"
  target_buffer: 1
  perf_event_config {{
    timebase {{ frequency: {frequency} }}
    callstack_sampling {{
      scope {{ target_cmdline: "{package}" }}
      kernel_frames: false
      user_frames: UNWIND_DWARF
    }}
    ring_buffer_pages: 256
    ring_buffer_read_period_ms: 100
  }}
}} }}''')
    for source in ("android.surfaceflinger.frametimeline", "android.gpu.memory", "gpu.renderstages"):
        if native_only and source == "android.surfaceflinger.frametimeline":
            continue
        if source in sources:
            chunks.append(f'data_sources {{ config {{ name: "{source}" target_buffer: 2 }} }}')
    if gpu_counter_ids:
        if "gpu.counters" not in sources:
            raise ValueError("GPU counters requested but gpu.counters is not registered")
        ids = "\n".join(f"counter_ids: {counter}" for counter in gpu_counter_ids)
        chunks.append(f'''data_sources {{ config {{ name: "gpu.counters" target_buffer: 2
  gpu_counter_config {{ counter_period_ns: 10000000 {ids} }}
}} }}''')
    return "\n".join(chunks) + "\n"


def quality_sql(package):
    # Package is restricted to identifiers by CLI validation.
    return f'''SELECT json_object(
  'durationMs', (SELECT (end_ts-start_ts)/1e6 FROM trace_bounds),
  'errors', (SELECT json_group_array(json_object('name',name,'index',idx,'value',value,'severity',severity))
    FROM stats WHERE value>0 AND severity IN ('error','data_loss')),
  'appCpuSamples', (SELECT count(*) FROM perf_sample s JOIN thread t ON t.utid=s.utid
    JOIN process p ON p.upid=t.upid WHERE p.name='{package}'),
  'appCallstackSamples', (SELECT count(*) FROM perf_sample s JOIN thread t ON t.utid=s.utid
    JOIN process p ON p.upid=t.upid WHERE p.name='{package}' AND s.callsite_id IS NOT NULL),
  'appUnwindErrors', (SELECT json_group_array(json_object('error',unwind_error,'samples',samples))
    FROM (SELECT s.unwind_error,count(*) AS samples FROM perf_sample s JOIN thread t ON t.utid=s.utid
      JOIN process p ON p.upid=t.upid WHERE p.name='{package}' AND s.unwind_error IS NOT NULL
      GROUP BY s.unwind_error)),
  'stackFrames', (SELECT count(*) FROM stack_profile_frame),
  'namedStackFrames', (SELECT count(*) FROM stack_profile_frame WHERE name IS NOT NULL AND name!=''),
  'appScheduledSlices', (SELECT count(*) FROM sched s JOIN thread t ON t.utid=s.utid
    JOIN process p ON p.upid=t.upid WHERE p.name='{package}'),
  'appRssSamples', (SELECT count(*) FROM counter c JOIN process_counter_track t ON t.id=c.track_id
    JOIN process p ON p.upid=t.upid WHERE p.name='{package}' AND t.name GLOB 'mem.rss*'),
  'appNativeSpans', (SELECT count(*) FROM slice s JOIN thread_track tt ON tt.id=s.track_id
    JOIN thread t ON t.utid=tt.utid JOIN process p ON p.upid=t.upid
    WHERE p.name='{package}' AND s.name GLOB 'CaraML.native.*' AND s.dur>=0),
  'incompleteCaraMLSpans', (SELECT count(*) FROM slice s WHERE s.name GLOB 'CaraML.*' AND s.dur=-1),
  'gpuSlices', (SELECT count(*) FROM gpu_slice)
) AS result;'''


def analyze(trace, processor, package, cpu_sampling, require_native_spans=False):
    query = trace.with_suffix(".quality.sql")
    query.write_text(quality_sql(package))
    result = subprocess.run([str(processor), "query", "-f", str(query), str(trace)],
                            capture_output=True, text=True, check=True)
    trace.with_suffix(".processor.log").write_text(result.stderr)
    lines = result.stdout.strip().splitlines()
    # trace_processor's JSON string cell is surrounded by quotes but its nested
    # quotes are not CSV-escaped. A single JSON column makes parsing unambiguous.
    payload = lines[-1]
    quality = json.loads(payload[1:-1] if payload.startswith('"{') else payload)
    failures = []
    if quality["errors"]:
        failures.append("trace processor reported error/data-loss stats")
    if not quality["appScheduledSlices"]:
        failures.append("no scheduler data for target app")
    if not quality["appRssSamples"]:
        failures.append("no RSS samples for target app")
    if cpu_sampling and not quality["appCallstackSamples"]:
        failures.append("no native call stacks: verify debuggable/profileable eligibility")
    if require_native_spans and not quality["appNativeSpans"]:
        failures.append("no native spans: install instrumented APK and enable app atrace")
    if quality["incompleteCaraMLSpans"]:
        failures.append("capture ended with incomplete CaraML operations")
    quality["failures"] = failures
    quality["valid"] = not failures
    trace.with_suffix(".quality.json").write_text(json.dumps(quality, indent=2) + "\n")
    return quality


def perfetto_main(argv=None):
    """Capture a bounded Android workload with scheduler, native CPU stacks and memory.

    This is diagnostic profiling, not an uninstrumented latency benchmark. The command after
    -- runs on the host (typically adb am instrument); no shell interpolation is used. App
    text/logcat are deliberately not collected. No root or device settings changes are
    required. A debuggable or shell-profileable app is needed for stacks."""
    parser = argparse.ArgumentParser(description=perfetto_main.__doc__)
    parser.add_argument("--serial")
    parser.add_argument("--package", default="com.debanshu777.caraml")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--seconds", type=int, default=180, help="Hard capture/workload bound")
    parser.add_argument("--frequency", type=int, default=99)
    parser.add_argument("--no-cpu-sampling", action="store_true")
    parser.add_argument("--native-only", action="store_true",
                        help="Keep app atrace, scheduling and memory; omit framework atrace and FrameTimeline")
    parser.add_argument("--gpu-counter-id", type=int, action="append", default=[])
    parser.add_argument("--query-file", type=Path, help="Use saved perfetto --query-raw capabilities")
    parser.add_argument("--config-only", action="store_true", help="Write config without device capture")
    parser.add_argument("--probe-only", action="store_true")
    parser.add_argument("--require-native-spans", action="store_true")
    parser.add_argument("--trace-processor", type=Path, default=ROOT / "build/tools/trace_processor")
    parser.add_argument("--analyze", type=Path, help="Validate an existing trace without device access")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    if not re.fullmatch(r"[A-Za-z0-9_.]+", args.package):
        parser.error("Package must be an Android package identifier")
    if not 5 <= args.seconds <= 600 or not 10 <= args.frequency <= 250:
        parser.error("Use 5–600 seconds and 10–250 Hz sampling")
    if any(counter < 0 for counter in args.gpu_counter_id):
        parser.error("GPU counter IDs must be nonnegative")
    if args.analyze:
        quality = analyze(args.analyze, args.trace_processor, args.package,
                          not args.no_cpu_sampling, args.require_native_spans)
        print(json.dumps(quality, indent=2))
        return 0 if quality["valid"] else 2
    if not args.serial and not (args.config_only and args.query_file):
        parser.error("--serial is required for device capture/probing")
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command and not (args.probe_only or args.config_only):
        parser.error("Provide the host workload command after --")
    try:
        require_raw_instrumentation(command)
    except ValueError as error:
        parser.error(str(error))
    output = args.output or ROOT / "build/benchmark-results/native-profile" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output.mkdir(parents=True, exist_ok=False)
    adb = ["adb", "-s", args.serial] if args.serial else []

    def shell(*command, check=True):
        # adb shell concatenates argv; quote each argument for the device shell.
        result = subprocess.run(adb + ["shell", shlex.join(command)], capture_output=True,
                                text=True)
        if result.returncode and check:
            with (output / "device-errors.txt").open("a") as log:
                log.write(f"{shlex.join(command)}\n{result.stdout}\n{result.stderr}\n")
            raise RuntimeError(f"Device command failed: {shlex.join(command)}\n{result.stderr}")
        return result.stdout.strip()

    query = args.query_file.read_bytes() if args.query_file else subprocess.check_output(adb + ["exec-out", "perfetto", "--query-raw"])
    (output / "capabilities.pb").write_bytes(query)
    sources = data_source_names(query)
    capabilities = {"dataSources": sorted(sources), "gpuExecutionAvailable": "gpu.renderstages" in sources,
                    "gpuCountersAvailable": "gpu.counters" in sources,
                    "gpuMemoryAvailable": "android.gpu.memory" in sources,
                    "captureProfile": "native-only" if args.native_only else "full"}
    (output / "capabilities.json").write_text(json.dumps(capabilities, indent=2) + "\n")
    config = make_config(args.package, sources, args.seconds, args.frequency,
                         not args.no_cpu_sampling, args.gpu_counter_id, args.native_only)
    (output / "capture.pbtxt").write_text(config)
    print(output, flush=True)
    if args.probe_only or args.config_only:
        print(json.dumps(capabilities, indent=2))
        return 0
    metadata = {"package": args.package, "serial": args.serial, "command": command,
                "captureProfile": capabilities["captureProfile"],
                "diagnosticOnly": True, "samplingHz": 0 if args.no_cpu_sampling else args.frequency,
                "gitRevision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                "device": shell("getprop", "ro.build.fingerprint"), "valid": False,
                "gpuExecutionAvailable": capabilities["gpuExecutionAvailable"]}
    for name, words in (("thermal-before", ("dumpsys", "thermalservice")),
                        ("battery-before", ("dumpsys", "battery")),
                        ("memory-before", ("dumpsys", "meminfo", args.package))):
        (output / f"{name}.txt").write_text(shell(*words, check=False))
    token = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    remote_trace = f"/data/misc/perfetto-traces/caraml-{token}.perfetto-trace"
    trace_pid = None
    pulled = False
    try:
        # Supply config on stdin: modern Android SELinux policies prevent the
        # perfetto domain reading arbitrary shell-created /data/local/tmp files.
        start = subprocess.run(adb + ["shell", shlex.join(("perfetto", "--background-wait", "--txt",
                               "-c", "-", "-o", remote_trace))], input=config,
                               capture_output=True, text=True)
        (output / "recorder-start.txt").write_text(start.stdout + "\n" + start.stderr)
        if start.returncode:
            raise RuntimeError(f"Perfetto failed to start: {start.stderr}")
        trace_pid = start.stdout.strip()
        if not trace_pid.isdigit():
            raise RuntimeError(f"Unexpected Perfetto PID: {trace_pid!r}")
        started = time.monotonic()
        with (output / "workload.txt").open("w") as log:
            try:
                result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=args.seconds - 2)
                metadata["workloadExitCode"] = result.returncode
            except subprocess.TimeoutExpired:
                metadata["workloadTimedOut"] = True
                shell("am", "force-stop", args.package, check=False)
                raise
            finally:
                metadata["workloadSeconds"] = time.monotonic() - started
                shell("kill", "-INT", trace_pid, check=False)
                # Stop first, then require a stable nonempty file before pulling.
                # A streaming trace can be stable while still recording, so the
                # PID check is essential; stability alone is insufficient.
                deadline = time.monotonic() + 30
                previous_size, stable = 0, 0
                while time.monotonic() < deadline:
                    # kill prints nothing on both outcomes; obtain the exit status.
                    probe = subprocess.run(adb + ["shell", f"kill -0 {trace_pid} 2>/dev/null"],
                                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                    size_text = shell("stat", "-c", "%s", remote_trace, check=False)
                    size = int(size_text) if size_text.isdigit() else 0
                    stable = stable + 1 if size > 0 and size == previous_size else 0
                    previous_size = size
                    if probe.returncode != 0 and stable >= 3:
                        break
                    time.sleep(0.2)
                else:
                    raise RuntimeError("Perfetto did not stop and flush a stable nonempty trace")
                trace = output / "capture.perfetto-trace"
                subprocess.run(adb + ["pull", remote_trace, str(trace)], check=True)
                if trace.stat().st_size != previous_size:
                    raise RuntimeError("Incomplete trace pull; remote copy retained")
                pulled = True
                trace_pid = None
        quality = analyze(trace, args.trace_processor, args.package, not args.no_cpu_sampling,
                          args.require_native_spans)
        metadata["workloadValidation"] = validate_workload(command, metadata.get("workloadExitCode"),
                                                          (output / "workload.txt").read_text())
        metadata["valid"] = quality["valid"] and metadata["workloadValidation"]["valid"]
        print(json.dumps(quality, indent=2), flush=True)
    finally:
        if trace_pid and trace_pid.isdigit():
            shell("kill", "-INT", trace_pid, check=False)
        if pulled:
            shell("rm", "-f", remote_trace, check=False)
        for name, words in (("thermal-after", ("dumpsys", "thermalservice")),
                            ("battery-after", ("dumpsys", "battery")),
                            ("memory-after", ("dumpsys", "meminfo", args.package))):
            (output / f"{name}.txt").write_text(shell(*words, check=False))
        (output / "run.json").write_text(json.dumps(metadata, indent=2) + "\n")
    return 0 if metadata["valid"] else 2


def summarize_simpleperf(ndk, record, output):
    sys.path.insert(0, str(ndk / "simpleperf"))
    from simpleperf_report_lib import GetReportLib
    lib = GetReportLib(str(record))
    lib.ShowIpForUnknownSymbol()
    cache = output / "binary_cache"
    if cache.exists():
        lib.SetSymfs(str(cache))
    leaves, ancestors, threads = Counter(), Counter(), Counter()
    samples, with_stack, native, unknown = 0, 0, 0, 0
    with (output / "samples.jsonl").open("w") as stream:
        while True:
            sample = lib.GetNextSample()
            if sample is None:
                break
            symbol = lib.GetSymbolOfCurrentSample()
            chain = lib.GetCallChainOfCurrentSample()
            frames = [(symbol.dso_name, symbol.symbol_name)]
            frames += [(chain.entries[i].symbol.dso_name, chain.entries[i].symbol.symbol_name)
                       for i in range(chain.nr)]
            samples += 1
            with_stack += bool(chain.nr)
            native += any("libllama" in path or "libggml" in path for path, _ in frames)
            unknown += symbol.symbol_name == "[unknown]" or symbol.symbol_name.startswith("0x")
            leaves[frames[0]] += 1
            ancestors.update(set(frames))
            threads[sample.thread_comm] += 1
            stream.write(json.dumps({"timeNs": sample.time, "pid": sample.pid, "tid": sample.tid,
                                     "thread": sample.thread_comm, "cpu": sample.cpu,
                                     "period": sample.period, "frames": frames}) + "\n")
    lib.Close()
    def top(counter):
        return [{"library": key[0], "function": key[1], "samples": count}
                for key, count in counter.most_common(60)]
    result = {"samples": samples, "samplesWithAncestors": with_stack, "samplesWithLlamaOrGgml": native,
              "unknownLeafSamples": unknown, "topLeaves": top(leaves), "topAncestors": top(ancestors),
              "threads": dict(threads), "validNativeAttribution": bool(samples and with_stack and native),
              "interpretation": "Sample counts are diagnostic attribution, not exact phase durations; ancestors overlap."}
    (output / "profile.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


def simpleperf_main(argv=None):
    """Run an existing workload under the NDK's non-root Simpleperf app profiler.

    Separate fallback when Perfetto provides scheduler samples but no native stacks. This
    force-stops the target package before recording to include native loading. Requires a
    debuggable/profileable target, an NDK and matching unstripped libraries."""
    parser = argparse.ArgumentParser(description=simpleperf_main.__doc__)
    parser.add_argument("--serial")
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--symbols", type=Path)
    parser.add_argument("--package", default="com.debanshu777.caraml")
    parser.add_argument("--seconds", type=int, default=30)
    parser.add_argument("--frequency", type=int, default=99)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--analyze", type=Path)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    args.ndk = args.ndk.resolve()
    if not (args.ndk / "simpleperf/app_profiler.py").is_file():
        parser.error("NDK Simpleperf helpers are missing")
    if args.analyze:
        output = (args.output or args.analyze.parent).resolve()
        result = summarize_simpleperf(args.ndk, args.analyze.resolve(), output)
        print(json.dumps({key: value for key, value in result.items() if not key.startswith("top")}, indent=2))
        return 0 if result["validNativeAttribution"] else 2
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not args.serial or not args.symbols or not command:
        parser.error("Capture requires --serial, --symbols and a workload command after --")
    try:
        require_raw_instrumentation(command)
    except ValueError as error:
        parser.error(str(error))
    if not re.fullmatch(r"[A-Za-z0-9_.]+", args.package):
        parser.error("Invalid package identifier")
    if not 10 <= args.seconds <= 120 or not 10 <= args.frequency <= 250:
        parser.error("Use 10–120 seconds and 10–250 Hz")
    output = (args.output or ROOT / "build/benchmark-results/simpleperf" /
              datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")).resolve()
    output.mkdir(parents=True, exist_ok=False)
    symbols = args.symbols.resolve()
    env = dict(os.environ, ANDROID_SERIAL=args.serial)
    adb = ["adb", "-s", args.serial]
    # Refuse concurrent Simpleperf ownership; the NDK helper's interrupt path
    # stops its on-device recorder by name.
    prior = subprocess.run(adb + ["shell", "pidof", "simpleperf"], capture_output=True, text=True)
    if prior.stdout.strip():
        raise RuntimeError("A Simpleperf recorder is already running")
    subprocess.run(adb + ["shell", "am", "force-stop", args.package], check=True)
    record = output / "perf.data"
    profile_command = [sys.executable, str(args.ndk / "simpleperf/app_profiler.py"),
                       "--disable_adb_root", "-p", args.package, "--ndk_path", str(args.ndk),
                       "-lib", str(symbols), "-o", str(record),
                       "-r", f"-e task-clock:u -f {args.frequency} --call-graph dwarf --no-callchain-joiner --keep-failed-unwinding-result --duration {args.seconds}"]
    metadata = {"diagnosticOnly": True, "workload": command, "profiler": profile_command,
                "valid": False, "package": args.package, "serial": args.serial}
    profiler = None
    try:
        with (output / "profiler.txt").open("w") as log:
            profiler = subprocess.Popen(profile_command, cwd=output, env=env, stdout=log, stderr=subprocess.STDOUT)
            # Wait until the helper has finished preparing/uploading symbols and
            # starts its app-scoped recorder. The recorder waits for app startup.
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                if profiler.poll() is not None:
                    raise RuntimeError("Simpleperf helper stopped before workload; inspect profiler.txt")
                if "run adb cmd:" in (output / "profiler.txt").read_text():
                    break
                time.sleep(0.1)
            else:
                raise RuntimeError("Simpleperf did not become ready within 120 seconds")
            time.sleep(0.3)
            with (output / "workload.txt").open("w") as workload:
                result = subprocess.run(command, stdout=workload, stderr=subprocess.STDOUT, timeout=args.seconds - 1)
                metadata["workloadExitCode"] = result.returncode
            metadata["profilerExitCode"] = profiler.wait(timeout=args.seconds + 120)
        if metadata["profilerExitCode"] != 0 or not record.is_file() or not record.stat().st_size:
            raise RuntimeError("Simpleperf did not produce a complete record; inspect profiler.txt")
        profile = summarize_simpleperf(args.ndk, record, output)
        metadata["workloadValidation"] = validate_workload(command, metadata["workloadExitCode"],
                                                          (output / "workload.txt").read_text())
        metadata["valid"] = profile["validNativeAttribution"] and metadata["workloadValidation"]["valid"]
        print(json.dumps({key: value for key, value in profile.items() if not key.startswith("top")}, indent=2))
    finally:
        if profiler and profiler.poll() is None:
            profiler.send_signal(signal.SIGINT)
            try:
                profiler.wait(timeout=30)
            except subprocess.TimeoutExpired:
                profiler.terminate()
        (output / "run.json").write_text(json.dumps(metadata, indent=2) + "\n")
    return 0 if metadata["valid"] else 2
