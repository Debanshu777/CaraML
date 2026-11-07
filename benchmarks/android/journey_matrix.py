#!/usr/bin/env python3
"""Run untraced, matched CPU/Vulkan installed-model journeys on an existing debug APK.

Build/install first. The default matrix alternates CPU and Vulkan, three trials each,
with context 4096, F16 KV, a fixed system/user prompt and greedy generation. No app
preferences, model files, display refresh policy, or tracing settings are changed.
Only screen timeout is temporarily extended and restored in finally.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import statistics
import subprocess
import sys
import time

from profiling import proto_fields
from source_archive import archive_untracked_sources

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.debanshu777.caraml"
INSTRUMENTATION = f"{PACKAGE}/{PACKAGE}.benchmark.PerformanceValidation"


def parse_events(raw):
    events = []
    for line in raw.splitlines():
        text = line.strip().removeprefix("INSTRUMENTATION_STATUS: stream=").strip()
        if not text.startswith("{"):
            continue
        value = json.loads(text)
        if isinstance(value, dict) and "stage" in value and "case" in value:
            events.append(value)
    return events


def parse_runtime_config(value):
    if not isinstance(value, str) or not value:
        return None
    fields = {}
    for field in value.split(","):
        pair = field.split("=")
        if len(pair) != 2 or not pair[0] or not pair[1] or pair[0] in fields:
            return None
        fields[pair[0]] = pair[1]
    return fields


def comparable_runtime_config(value):
    """Compare validated trials while allowing only backend placement differences."""
    fields = parse_runtime_config(value)
    if fields is None:
        raise ValueError("Cannot compare a malformed runtime configuration")
    return tuple(sorted((key, value) for key, value in fields.items()
                        if key not in {"gpu", "offloadKqv"}))


def parse_conditions(thermal, battery, power, policy, window):
    status = re.search(r"^Thermal Status:\s*(\d+)\s*$", thermal, re.MULTILINE)
    temperature = re.search(r"^\s*temperature:\s*(\d+)\s*$", battery, re.MULTILINE)
    # Prefer the delegate's explicit showing state; do not infer unlock from focus alone.
    delegate = re.search(r"KeyguardServiceDelegate[\s\S]*?\bshowing=(true|false)", policy)
    states = re.findall(r"\b(?:mKeyguardShowing|mShowingLockscreen|isStatusBarKeyguard)=(true|false)", policy + "\n" + window)
    if delegate:
        states.append(delegate[1])
    # Android 17's `dumpsys window windows` omits focus; the full dump includes
    # global/display focus. Accept only an explicit non-null window, never a
    # merely focusable window flag or an application/task record.
    focus_candidates = re.findall(
        r"^\s*(?:mCurrentFocus|mFocusedWindow)\s*=\s*(Window\{[^\r\n]+|null)\s*$",
        window, re.MULTILINE,
    )
    focused = next((value.strip() for value in focus_candidates if value.strip() != "null"), None)
    return {
        "thermalStatus": int(status[1]) if status else None,
        "batteryTemperatureC": int(temperature[1]) / 10 if temperature else None,
        "awake": "mWakefulness=Awake" in power,
        "keyguardShowing": True if "true" in states else False if states else None,
        "focusedWindow": focused,
        "focusCandidates": focus_candidates,
    }


def validate_events(events, raw, returncode, case, phase, backend, context, apk_sha):
    problems = []

    def one(stage, owner=case):
        found = [e for e in events if e.get("case") == owner and e.get("stage") == stage]
        if len(found) != 1:
            problems.append(f"Expected one {owner}/{stage}, found {len(found)}")
            return {}
        return found[0]

    if returncode != 0 or not re.search(r"^INSTRUMENTATION_CODE: -1\s*$", raw, re.MULTILINE):
        problems.append("Instrumentation did not finish with Activity.RESULT_OK")
    if "FAILURES!!!" in raw or "INSTRUMENTATION_FAILED" in raw:
        problems.append("Instrumentation reported a failure")
    if one("outcome").get("result") != "PASS":
        problems.append("Case outcome was not PASS")
    if any(e.get("stage") in {"failure", "conditioned_skip"} for e in events):
        problems.append("Harness reported failure or conditioned skip")
    start = one("start", "ENVIRONMENT")
    if start.get("diagnosticOnly") is True or start.get("vulkanTiming") is True:
        problems.append("Diagnostic Vulkan timing cannot be used as an untraced measurement")
    if start.get("activeDownloads") != 0 or start.get("requestedBackend") != backend or start.get("sampling") != "greedy":
        problems.append("Download/backend/sampling conditions differ from the requested workload")
    if start.get("requestedContext") != context or start.get("requestedKv") != "F16_F16":
        problems.append("Harness did not acknowledge pinned context and KV settings")
    if one("installed_apk", "ENVIRONMENT").get("sha256") != apk_sha:
        problems.append("Harness APK identity differs from the installed APK")
    foreground = one("foreground", "ENVIRONMENT")
    conditioned = one("case_conditioning")
    if (foreground.get("resumed") is not True or foreground.get("focused") is not True
            or conditioned.get("foregroundResumed") is not True
            or conditioned.get("foregroundFocused") is not True
            or foreground.get("cpuAllowedList") != "0-7" or conditioned.get("cpuAllowedList") != "0-7"):
        problems.append("Foreground activity/CPU affinity was not verified before work")
    if one("verified_identity").get("pinned") is not True:
        problems.append("Installed model identity was not pinned")
    if one("prepare").get("state") != "Ready" or one("resolve").get("state") != "Ready":
        problems.append("Installed-model preparation or resolution was not ready")
    load = one("load")
    if load.get("state") != "Success" or load.get("context") != context or load.get("temperature") != 0:
        problems.append("Loaded context/state/temperature does not match the workload")
    config = load.get("config", "")
    if not isinstance(config, str) or not re.search(r"(?:^|,)kv=1/1(?:,|$)", config):
        problems.append("Effective native KV types were not F16/F16")
    placement = one("placement")
    gpu = placement.get("gpuLayers")
    if placement.get("requestedBackend") != backend or not isinstance(gpu, int) or (gpu != 0 if backend == "cpu" else gpu <= 0):
        problems.append("Effective GPU placement does not match requested backend")
    generations = [e for e in events if e.get("case") == case and e.get("stage") == "generation"]
    expected_turns = {1, 2, 3} if phase == "journey" else {1} if phase == "smoke" else set()
    if len(generations) != len(expected_turns) or {e.get("turn") for e in generations} != expected_turns:
        problems.append("Generation count/turns do not match the selected phase")
    for generation in generations:
        if (not isinstance(generation.get("tokens"), int) or not 1 <= generation["tokens"] < 64
                or generation.get("naturalEog") is not True or generation.get("capReached") is not False
                or generation.get("deltaMatchesFinal") is not True or generation.get("contentBytes", 0) <= 0
                or not re.fullmatch(r"[0-9a-f]{64}", generation.get("contentSha256", ""))):
            problems.append(f"Turn {generation.get('turn')} failed output/termination checks")
    if phase == "journey":
        requested = one("requested_workload")
        resolved = one("resolved_plan")
        dimensions = {"context": context, "batch": 256, "microBatch": 64,
                      "sequenceCount": 1, "keyCacheType": "F16", "valueCacheType": "F16"}
        if (start.get("workloadPolicy") != "FIXED_NO_FALLBACK"
                or requested.get("policy") != "FIXED_NO_FALLBACK"
                or requested.get("sequenceCount") != 1
                or requested.get("placementPolicy") != "FULL_VULKAN"
                or type(requested.get("fullVulkanLayerCount")) is not int or requested.get("fullVulkanLayerCount", 0) <= 0
                or (backend == "vulkan" and requested.get("fullVulkanLayerCount") != gpu)
                or resolved.get("gpuLayers") != gpu or resolved.get("nativeMetadataFallback") is not False
                or any(requested.get(key) is not False for key in
                       ("allowContextFallback", "allowBatchFallback", "allowKvCacheFallback"))
                or any(requested.get(key) != value or resolved.get(key) != value
                       for key, value in dimensions.items())
                or str(resolved.get("backend", "")).lower() != backend):
            problems.append("Requested/resolved exact workload or disabled fallback policy was not verified")
        expected_runtime = {"ctx": str(context), "b": "256", "ub": "64", "kv": "1/1",
                            "gpu": str(gpu), "autoFit": "false",
                            "offloadKqv": "true" if backend == "vulkan" else "false"}
        runtime = parse_runtime_config(config)
        if runtime is None or any(runtime.get(key) != value for key, value in expected_runtime.items()):
            problems.append("Configured runtime differs from exact context/batch/ubatch/KV/backend workload")
        resident = one("resident_request")
        if resident.get("state") != "Success":
            problems.append(f"Repeated repository request was not successful: {resident.get('state')}")
        if resident.get("context") != context:
            problems.append(f"Repeated repository request context {resident.get('context')} differs from required {context}")
        if resident.get("scope") != "REPOSITORY_REQUEST":
            problems.append("Repeated repository request measurement scope was not verified")
        if parse_runtime_config(resident.get("config")) != runtime or resident.get("gpuLayers") != gpu:
            problems.append("Repeated repository request changed runtime configuration or placement")
    for event in events:
        if "thermal" in event and event["thermal"] != 0:
            problems.append(f"Non-normal/unknown thermal status at {event.get('stage')}")
    for event in [load, *generations]:
        for key in ("wholeLoadMs",) if event is load else ("ttftMs", "wallMs"):
            if not isinstance(event.get(key), (int, float)) or event[key] <= 0:
                problems.append(f"Missing/nonpositive {key} at {event.get('stage')}")
    return problems


class Archive:
    def __init__(self, output):
        self.output = output
        self.index = 0
        (output / "commands").mkdir(parents=True)

    def command(self, argv, timeout=30, check=True, binary=False):
        self.index += 1
        name = f"commands/{self.index:04d}"
        started = datetime.now(timezone.utc).isoformat()
        clock = time.monotonic()
        timed_out = False
        try:
            result = subprocess.run(argv, cwd=ROOT, capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired as failure:
            timed_out = True
            result = subprocess.CompletedProcess(argv, -1, failure.stdout or b"", failure.stderr or b"")
        (self.output / f"{name}.stdout").write_bytes(result.stdout)
        (self.output / f"{name}.stderr").write_bytes(result.stderr)
        with (self.output / "commands.jsonl").open("a") as log:
            log.write(json.dumps({"argv": argv, "startedAtUtc": started, "elapsedSeconds": time.monotonic() - clock,
                                  "returncode": result.returncode, "timedOut": timed_out,
                                  "stdout": f"{name}.stdout", "stderr": f"{name}.stderr"}) + "\n")
        if timed_out or (check and result.returncode != 0):
            raise RuntimeError(f"Command {'timed out' if timed_out else 'failed'}; see {name}.stderr: {argv}")
        if binary:
            return result.stdout
        return result.stdout.decode("utf-8", errors="replace").strip(), result.returncode


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--label", default="baseline")
    parser.add_argument("--backend", choices=("both", "cpu", "vulkan"), default="both")
    parser.add_argument("--first-backend", choices=("cpu", "vulkan"), default="cpu")
    parser.add_argument("--trials", type=int, default=3, help="Trials per backend, 1..5")
    parser.add_argument("--phase", choices=("journey", "smoke", "load"), default="journey")
    parser.add_argument("--case", default="qwen2.5-3b", help="One installed harness case")
    parser.add_argument("--context-tokens", type=int, default=4096)
    parser.add_argument("--max-start-temperature", type=float, default=35.0)
    parser.add_argument("--cooldown-seconds", type=int, default=900)
    parser.add_argument("--trial-timeout-seconds", type=int, default=900)
    args = parser.parse_args(argv)
    if not re.fullmatch(r"[A-Za-z0-9_-]+", args.label) or not re.fullmatch(r"[a-z0-9.-]+", args.case):
        parser.error("Use a simple label and a single harness case")
    if args.case in {"six", "all"}:
        parser.error("The matrix requires one installed case, not a suite selector")
    if not 1 <= args.trials <= 5 or not 512 <= args.context_tokens <= 16384:
        parser.error("Use 1..5 trials and 512..16384 context tokens")
    if not 20 <= args.max_start_temperature <= 35 or not 0 <= args.cooldown_seconds <= 1800 or not 30 <= args.trial_timeout_seconds <= 1800:
        parser.error("Use a 20..35 C ceiling, 0..1800 s cooling, and 30..1800 s trial timeout")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    output = ROOT / "build/benchmark-results/journey-matrix" / f"{stamp}-{args.label}"
    archive = Archive(output)
    adb = ["adb", "-s", args.serial]

    def shell(*command, **kwargs):
        return archive.command(adb + ["shell", *command], **kwargs)[0]

    def save(name, value):
        (output / name).write_text(json.dumps(value, indent=2) + "\n")

    def conditions(name):
        raw = {key: shell("dumpsys", service) for key, service in (
            ("thermal", "thermalservice"), ("battery", "battery"), ("power", "power"))}
        raw["policy"] = shell("dumpsys", "window", "policy")
        raw["window"] = shell("dumpsys", "window")
        parsed = parse_conditions(**raw)
        save(f"{name}-conditions.json", {"parsed": parsed, "raw": raw})
        return parsed

    def require_unlocked(snapshot):
        if snapshot["awake"] is not True or snapshot["keyguardShowing"] is not False:
            raise RuntimeError("Device is not confirmed awake and unlocked; unlock it before running")
        if not snapshot["focusedWindow"] or snapshot["focusedWindow"] == "null":
            raise RuntimeError("No foreground window before work")

    def require_untraced(name):
        query = archive.command(adb + ["exec-out", "perfetto", "--query-raw"], binary=True)
        (output / f"{name}-perfetto-state.pb").write_bytes(query)
        started = [value for field, wire, value in proto_fields(query) if field == 4 and wire == 0]
        sessions = [value for field, wire, value in proto_fields(query) if field == 3 and wire == 0]
        # Proto optional numeric fields may omit their default zero value.
        if len(sessions) != 1 or len(started) > 1 or (started and started[0] != 0):
            raise RuntimeError("An active Perfetto session exists, or its state is unknown; stop diagnostics before timing")
        processes = shell("ps", "-A", "-o", "NAME")
        if any(line.strip() == "simpleperf" for line in processes.splitlines()):
            raise RuntimeError("A profiling process is active; stop diagnostics before timing")

    def preferences_hash():
        raw, code = archive.command(adb + ["shell", "run-as", PACKAGE, "sha256sum", "files/app.preferences_pb"], check=False)
        if code == 0 and re.fullmatch(r"[0-9a-f]{64}\s+files/app\.preferences_pb", raw):
            return raw.split()[0]
        # Failure to read an existing settings file must not be mistaken for no preferences.
        exists, exists_code = archive.command(adb + ["shell", "run-as", PACKAGE, "ls", "files"], check=False)
        if exists_code != 0 or "app.preferences_pb" in exists.splitlines():
            raise RuntimeError("Cannot establish the user-preferences hash")
        return None

    metadata = {"schemaVersion": 1, "startedAtUtc": stamp, "options": vars(args), "trials": [],
                "diagnosticTracing": False, "allTrialsValid": False, "managedBuild": "DEBUG",
                "nativeBuild": "RELEASE", "contextTokens": args.context_tokens, "kvPreset": "F16_F16",
                "sampling": "greedy", "cacheState": "FRESH_PROCESS_OS_CACHE_UNCONTROLLED",
                "residentScope": "REPOSITORY_REQUEST_NOT_UI_PICKER", "screenTimeoutRestored": False}
    timeout_before = None
    prefs_before = None
    prefs_established = False
    try:
        if archive.command(adb + ["get-state"])[0] != "device":
            raise RuntimeError("Device must be connected and authorized")
        if shell("getprop", "ro.kernel.qemu") == "1" or int(shell("getprop", "ro.build.version.sdk")) < 29:
            raise RuntimeError("Use a physical Android 10+ device")
        metadata["fingerprint"] = shell("getprop", "ro.build.fingerprint")
        metadata["gitRevision"] = archive.command(["git", "rev-parse", "HEAD"])[0]
        metadata["gitStatus"] = archive.command(["git", "status", "--short"])[0]
        metadata["submodules"] = archive.command(["git", "submodule", "status"])[0]
        patch = archive.command(["git", "diff", "HEAD", "--binary"], binary=True)
        (output / "working-tree.patch").write_bytes(patch)
        metadata["nativeSourceState"] = {}
        for relative in ("libraries/llama.cpp", "libraries/stable-diffusion.cpp"):
            if not (ROOT / relative).is_dir():
                continue
            native_name = relative.removeprefix("libraries/")
            native_diff = archive.command(["git", "-C", relative, "diff", "HEAD", "--binary"], binary=True)
            (output / f"{native_name}-working-tree.patch").write_bytes(native_diff)
            metadata["nativeSourceState"][relative] = {
                "revision": archive.command(["git", "-C", relative, "rev-parse", "HEAD"])[0],
                "status": archive.command(["git", "-C", relative, "status", "--short"])[0],
            }
        untracked = archive.command(["git", "ls-files", "--others", "--exclude-standard", "-z"], binary=True)
        source_manifest = archive_untracked_sources(
            ROOT, output, [Path(value.decode("utf-8")) for value in untracked.split(b"\0") if value],
            capture_phase="before measurement of an already installed APK; not proof of its build inputs",
        )
        metadata["untrackedSources"] = [entry["path"] for entry in source_manifest["files"]]
        metadata["untrackedSourcesManifest"] = "untracked-sources-manifest.json"
        apk_lines = shell("pm", "path", PACKAGE).splitlines()
        if len(apk_lines) != 1 or not apk_lines[0].startswith("package:"):
            raise RuntimeError("Install the single debug APK before running")
        apk_path = apk_lines[0].removeprefix("package:")
        metadata["installedApkPath"] = apk_path
        metadata["installedApkSha256"] = shell("sha256sum", apk_path).split()[0]
        metadata["installedApkBytes"] = int(shell("stat", "-c", "%s", apk_path))
        prefs_before = preferences_hash()
        prefs_established = True
        metadata["preferencesSha256Before"] = prefs_before
        if shell("settings", "get", "global", "low_power") != "0":
            raise RuntimeError("Disable battery saver before benchmarking")
        initial = conditions("initial")
        require_unlocked(initial)
        timeout_before = shell("settings", "get", "system", "screen_off_timeout")
        metadata["screenTimeoutBefore"] = timeout_before
        shell("settings", "put", "system", "screen_off_timeout", "1800000")
        order = [args.first_backend, "vulkan" if args.first_backend == "cpu" else "cpu"] if args.backend == "both" else [args.backend]
        print(output, flush=True)
        sequence = [(trial, backend) for trial in range(1, args.trials + 1) for backend in order]
        for index, (trial, backend) in enumerate(sequence, 1):
            name = f"{index:02d}-{backend}-trial-{trial}"
            deadline = time.monotonic() + args.cooldown_seconds
            while True:
                before = conditions(f"{name}-before")
                require_unlocked(before)
                if before["thermalStatus"] == 0 and before["batteryTemperatureC"] is not None and before["batteryTemperatureC"] <= args.max_start_temperature:
                    break
                if before["thermalStatus"] is None or before["batteryTemperatureC"] is None:
                    raise RuntimeError("Unknown thermal status or battery temperature")
                if time.monotonic() >= deadline:
                    raise RuntimeError("Device did not meet starting conditions within bounded cooling")
                print(f"Cooling before {name}: {before['thermalStatus']}, {before['batteryTemperatureC']} C", flush=True)
                time.sleep(min(10, max(0, deadline - time.monotonic())))
            require_untraced(name)
            if shell("sha256sum", apk_path).split()[0] != metadata["installedApkSha256"]:
                raise RuntimeError("Installed APK changed during the matrix")
            shell("am", "force-stop", PACKAGE)
            device_start = shell("date", "+%s")
            command = adb + ["shell", "am", "instrument", "-w", "-r", "-e", "case", args.case,
                             "-e", "phase", args.phase, "-e", "label", "AFTER" if args.label.lower().startswith("after") else "BASELINE",
                             "-e", "trial", str(trial), "-e", "backend", backend,
                             "-e", "contextTokens", str(args.context_tokens), "-e", "kvPreset", "F16_F16",
                             "-e", "sampling", "greedy", "-e", "maxStartThermal", "0", INSTRUMENTATION]
            print(f"Running {name} ({args.phase}, untraced)", flush=True)
            clock = time.monotonic()
            try:
                raw, code = archive.command(command, timeout=args.trial_timeout_seconds, check=False)
            except BaseException:
                shell("am", "force-stop", PACKAGE)
                raise
            wall = time.monotonic() - clock
            (output / f"{name}.txt").write_text(raw + "\n")
            logs = shell("logcat", "-d", "-v", "threadtime", "-T", f"{device_start}.000", "-s",
                         "ModelLoad:I", "Inference:I", "LlamaRunner:I", "AndroidRuntime:E", "*:S")
            (output / f"{name}-stages.log").write_text(logs + "\n")
            after = conditions(f"{name}-after")
            events = parse_events(raw)
            (output / f"{name}-events.jsonl").write_text("".join(json.dumps(event) + "\n" for event in events))
            problems = validate_events(events, raw, code, args.case, args.phase, backend, args.context_tokens, metadata["installedApkSha256"])
            if after["thermalStatus"] != 0:
                problems.append("Thermal status was not NORMAL after the trial")
            if after["keyguardShowing"] is not False or after["awake"] is not True:
                problems.append("Device was no longer awake/unlocked after the trial")
            load = next((e for e in events if e.get("case") == args.case and e.get("stage") == "load"), {})
            record = {"index": index, "trial": trial, "backend": backend, "command": command,
                      "before": before, "after": after, "hostWallSeconds": wall, "events": events,
                      "wholeLoadMs": load.get("wholeLoadMs"), "measurementValid": not problems, "problems": problems}
            save(f"{name}.json", record)
            metadata["trials"].append(record)
            save("run.json", metadata)
            print(json.dumps({"trial": name, "wholeLoadMs": record["wholeLoadMs"], "valid": not problems, "problems": problems}), flush=True)
            if problems:
                raise RuntimeError(f"Invalid measurement in {name}; inspect its archived events")
        require_untraced("final")
        metadata["summary"] = {}
        for backend in order:
            trials = [record for record in metadata["trials"] if record["backend"] == backend]
            generations = [(record["trial"], event) for record in trials for event in record["events"] if event.get("stage") == "generation"]
            metadata["summary"][backend] = {
                "wholeLoadMsMedian": statistics.median(record["wholeLoadMs"] for record in trials),
                "ttftMsMedianByTurn": {str(turn): statistics.median(event["ttftMs"] for _, event in generations if event["turn"] == turn)
                                       for turn in sorted({event["turn"] for _, event in generations})},
                "runtimeConfigs": sorted({event["config"] for record in trials for event in record["events"] if event.get("stage") == "load"}),
            }
        configs = {comparable_runtime_config(event["config"])
                   for record in metadata["trials"] for event in record["events"] if event.get("stage") == "load"}
        metadata["runtimeConfigurationComparisonExcludedFields"] = ["gpu", "offloadKqv"]
        metadata["matchedRuntimeConfigurationExceptPlacement"] = len(configs) == 1
        if len(configs) != 1:
            raise RuntimeError("Effective runtime configurations differ beyond backend GPU placement/offload; matrix is not matched")
        metadata["allTrialsValid"] = True
    except BaseException as failure:
        metadata["allTrialsValid"] = False
        metadata["failure"] = {"type": type(failure).__name__, "message": str(failure)}
        raise
    finally:
        cleanup_errors = []
        if timeout_before is not None:
            try:
                if timeout_before == "null":
                    shell("settings", "delete", "system", "screen_off_timeout")
                else:
                    shell("settings", "put", "system", "screen_off_timeout", timeout_before)
                metadata["screenTimeoutRestored"] = shell("settings", "get", "system", "screen_off_timeout") == timeout_before
                if not metadata["screenTimeoutRestored"]:
                    cleanup_errors.append("Screen timeout restoration did not verify")
            except Exception as failure:
                cleanup_errors.append(f"Screen timeout restoration failed: {failure}")
        if prefs_established:
            try:
                metadata["preferencesSha256After"] = preferences_hash()
                metadata["preferencesPreserved"] = metadata["preferencesSha256After"] == prefs_before
                if not metadata["preferencesPreserved"]:
                    cleanup_errors.append("App preferences changed during the run; no settings file was overwritten")
            except Exception as failure:
                cleanup_errors.append(f"Preference verification failed: {failure}")
        if cleanup_errors:
            metadata["allTrialsValid"] = False
            metadata["cleanupErrors"] = cleanup_errors
        metadata["finishedAtUtc"] = datetime.now(timezone.utc).isoformat()
        save("run.json", metadata)
        if cleanup_errors and sys.exc_info()[0] is None:
            raise RuntimeError("; ".join(cleanup_errors))


if __name__ == "__main__":
    main()
