#!/usr/bin/env python3
"""Measure installed ONNX app/runtime paths without rebuilding or downloading.

Each trial starts a fresh process; OS file caches are preserved/uncontrolled. Use
--trace for a separate diagnostic pass, never for an untraced latency comparison.
Only screen timeout changes temporarily; app preferences and model files stay intact.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path, PurePosixPath
import re
import shlex
import shutil
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "benchmarks/android"))
from profiling import proto_fields, validate_workload
from journey_matrix import Archive, parse_conditions
from source_archive import archive_untracked_sources

PACKAGE = "com.debanshu777.caraml"
CASES = {name: name + "-onnx" for name in ("smollm2-135m", "smollm2-360m", "qwen25-05b", "qwen3-06b")}
CASES["llama32-1b"] = "llama32-1b-q4-onnx"


def positive(value, allow_zero=False):
    return (type(value) in (int, float) and math.isfinite(value)
            and (value >= 0 if allow_zero else value > 0))


def strict_object(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise ValueError(f"Duplicate JSON field: {name}")
        result[name] = value
    return result


def pinned_files(manifest, directory):
    """Verify small local Git blobs; use pinned LFS SHA256 for large weights.

    No model-weight reads here or inside a measured trial. The runtime device
    bundle is hashed once separately before cooling/timing.
    """
    result = []
    names = set()
    for entry in manifest["files"]:
        name = entry["rfilename"]
        path = PurePosixPath(name)
        if (not name or path.is_absolute() or ".." in path.parts or path.as_posix() != name
                or name in names or type(entry["size"]) is not int or entry["size"] <= 0):
            raise ValueError("Invalid pinned file path/size or duplicate entry")
        names.add(name)
        local = directory / name
        if local.stat().st_size != entry["size"]:
            raise ValueError(f"Local file size differs from pin: {name}")
        digest = entry.get("lfs", {}).get("sha256")
        if digest is None:
            if entry["size"] > 32 * 1024 * 1024:
                raise ValueError(f"Large artifact needs a pinned LFS hash: {name}")
            content = local.read_bytes()
            blob = hashlib.sha1(f"blob {len(content)}\0".encode() + content).hexdigest()
            if blob != entry.get("blobId"):
                raise ValueError(f"Local Git blob differs from pin: {name}")
            digest = hashlib.sha256(content).hexdigest()
        if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError(f"Invalid pinned SHA256: {name}")
        entry["expectedSha256"] = digest
        result.append({"name": name, "bytes": entry["size"], "sha256": digest})
    if manifest["graph"] not in names:
        raise ValueError("Pinned graph is absent from file set")
    return result


def runtime_bundle_path(model_path, graph):
    path = PurePosixPath(model_path)
    if path.is_absolute() or ".." in path.parts or path.as_posix() != model_path:
        raise ValueError("Invalid runtime model relative path")
    if model_path == graph:
        prefix = ""
    elif model_path.endswith("/" + graph):
        prefix = model_path[:-len(graph)]
    else:
        raise ValueError("Runtime model path must end in the pinned graph path")
    return PurePosixPath(f"/sdcard/Android/data/{PACKAGE}/files/benchmarks") / prefix


def parse_events(raw, mode):
    prefix = "CARAML_ONNX_PERF " if mode == "app" else "CARAML_BENCH "
    events = []
    for line in raw.splitlines():
        line = line.strip().removeprefix("INSTRUMENTATION_STATUS: stream=").strip()
        if not line.startswith(prefix):
            continue
        value = json.loads(line[len(prefix):], object_pairs_hook=strict_object)
        if not isinstance(value, dict):
            raise ValueError("Harness record must be a JSON object")
        key = "stage" if mode == "app" else "event"
        if not isinstance(value.get(key), str):
            raise ValueError(f"Harness record is missing {key}")
        events.append(value)
    return events


def validate_events(events, raw, returncode, command, mode, case, runs, tokens,
                    threads=2, model_path=None, expected_prompt_ids=None, manifest=None, reselect=None, memory_sampling=None):
    problems = list(validate_workload(command, returncode, raw)["failures"])
    key = "stage" if mode == "app" else "event"

    def one(stage):
        found = [row for row in events if row.get(key) == stage]
        if len(found) != 1:
            problems.append(f"Expected exactly one {stage}, found {len(found)}")
            return {}
        return found[0]

    if any(row.get(key) in {"error", "failure"} for row in events):
        problems.append("Harness reported an error")
    if mode == "app" and any(row.get("case") != case for row in events):
        problems.append("App case does not match requested case")
    environment = one("environment")
    if any(environment.get(field) is not True for field in
           ("foreground_resumed", "foreground_focused", "display_interactive")):
        problems.append("Initial foreground/display state was not verified")
    if one("outcome").get("result") != "PASS":
        problems.append("Explicit outcome is not PASS")
    load = one("load")
    if not positive(load.get("load_ms")):
        problems.append("Invalid runtime/repository load duration")
    runtime = load.get("runtime")
    if not isinstance(runtime, str) or not re.fullmatch(r"ONNX Runtime [^;]+; CPU; [1-9][0-9]* threads", runtime):
        problems.append("Effective plain CPU runtime was not verified")
    generations = [row for row in events if row.get(key) == "generation"]
    if (any(type(row.get("iteration")) is not int for row in generations)
            or [row.get("iteration") for row in generations] != list(range(runs + 1))
            or any(row.get("warmup") is not (index == 0) for index, row in enumerate(generations))):
        problems.append("Expected one warmup followed by each measured generation exactly once")
    for row in generations:
        if (type(row.get("tokens")) is not int or not 0 < row["tokens"] <= tokens
                or not isinstance(row.get("text"), str) or not row["text"].strip()
                or row.get("foreground_verified") is not True
                or not positive(row.get("ttft_ms")) or not positive(row.get("elapsed_ms"))
                or row.get("ttft_ms", math.inf) > row.get("elapsed_ms", -1)):
            problems.append(f"Generation {row.get('iteration')} has invalid output/timing/foreground")
        if row.get("thermal_start") != 0 or row.get("thermal_end") != 0:
            problems.append(f"Generation {row.get('iteration')} is not thermal NORMAL")
        if mode == "app":
            text = row.get("text", "")
            if (row.get("output_valid") is not True or type(row.get("stop_reason")) is not int or row.get("stop_reason") != 1
                    or row.get("token_count_scope") != "NONEMPTY_STREAM_CHUNKS"
                    or type(row.get("tokens")) is not int or row.get("tokens", tokens) >= tokens
                    or not isinstance(text, str)
                    or row.get("output_sha256") != hashlib.sha256(text.encode("utf-8")).hexdigest()):
                problems.append(f"Generation {row.get('iteration')} did not naturally complete with verified output")
        elif (not positive(row.get("decode_tokens_per_second"), allow_zero=row.get("tokens") == 1)
              or not positive(row.get("end_to_end_tokens_per_second"))):
            problems.append(f"Generation {row.get('iteration')} has invalid throughput")
    for row in events:
        for field in ("thermal", "thermal_start", "thermal_end"):
            if field in row and (type(row[field]) is not int or row[field] != 0):
                problems.append(f"Non-normal/unknown {field} at {row.get(key)}")
    if mode == "app":
        if memory_sampling is not None and environment.get("memory_sampling") is not memory_sampling:
            problems.append("App memory-sampling mode was not explicitly verified")
        if environment.get("thermal") != 0:
            problems.append("App environment thermal status is missing")
        plan = one("plan")
        if (plan.get("context") != 2048 or plan.get("backend") != "CPU"
                or plan.get("key_cache") != "F32" or plan.get("value_cache") != "F32"):
            problems.append("Admitted app workload is not context2048/CPU/F32 KV")
        for field in ("prepare_ms", "resolve_ms", "whole_load_ms"):
            if not positive(load.get(field)):
                problems.append(f"Invalid {field}")
        if all(positive(load.get(field)) for field in ("prepare_ms", "resolve_ms", "load_ms", "whole_load_ms")):
            if sum(load[field] for field in ("prepare_ms", "resolve_ms", "load_ms")) > load["whole_load_ms"] + 1:
                problems.append("Subphases exceed whole installed load")
        selections = [row for row in events if row.get(key) == "reselect"]
        if reselect is None:
            if selections:
                problems.append("Unexpected reselection changed the requested workload")
        else:
            selection = one("reselect")
            if (selection.get("mode") != reselect or selection.get("reused") is not (reselect == "reuse")
                    or selection.get("context") != 2048 or selection.get("runtime") != runtime
                    or selection.get("foreground_verified") is not True
                    or selection.get("thermal_start") != 0 or selection.get("thermal_end") != 0
                    or not positive(selection.get("elapsed_ms"))
                    or selection.get("scope") != "PREPARE_AND_REPOSITORY_RESELECT"):
                problems.append("Reselection did not verify requested reuse/reload, configuration and ready state")
            if len(selections) == 1 and load and generations:
                if not events.index(load) < events.index(selection) < events.index(generations[0]):
                    problems.append("Reselection must occur after initial load and before generation")
        repeated = one("repeat_load")
        for name, row in (("load", load), ("repeat_load", repeated)):
            if (row.get("context") != 2048 or row.get("foreground_verified") is not True
                    or row.get("thermal_start") != 0 or row.get("thermal_end") != 0
                    or not positive(row.get("load_ms"))):
                problems.append(f"Invalid {name} context/foreground/thermal/timing")
        if repeated.get("runtime") != runtime or repeated.get("scope") != "REPOSITORY_REQUEST":
            problems.append("Repeated repository load changed runtime or scope")
        if one("complete").get("success") is not True:
            problems.append("App completion was not successful")
        identities = one("identity").get("files")
        if not isinstance(identities, list) or not identities:
            problems.append("Missing installed bundle identity")
        else:
            names = [x.get("name") for x in identities if isinstance(x, dict)]
            if (len(names) != len(identities) or any(not isinstance(name, str) for name in names)
                    or len(set(names)) != len(names)
                    or any(not isinstance(x.get("name"), str) or type(x.get("bytes")) is not int
                           or x["bytes"] <= 0 or not re.fullmatch(r"[0-9a-f]{64}", str(x.get("sha256")))
                           for x in identities)):
                problems.append("Malformed/duplicate installed bundle identity")
            if manifest:
                actual = {x.get("name"): x for x in identities if isinstance(x, dict) and isinstance(x.get("name"), str)}
                for expected in manifest["files"]:
                    item = actual.get(expected["rfilename"], {})
                    digest = expected.get("expectedSha256") or expected.get("lfs", {}).get("sha256")
                    if item.get("bytes") != expected["size"] or (digest and item.get("sha256") != digest):
                        problems.append(f"Installed identity differs from pinned manifest: {expected['rfilename']}")
                if set(actual) != {x["rfilename"] for x in manifest["files"]}:
                    problems.append("Installed bundle file set differs from pinned manifest")
    else:
        if (load.get("model") != model_path or any(row.get("model") != model_path for row in generations)
                or not isinstance(runtime, str) or not runtime.endswith(f"; CPU; {threads} threads")):
            problems.append("Runtime model/thread configuration differs from request")
        if load.get("thermal") != 0:
            problems.append("Runtime load thermal status missing")
        prompt = one("prompt_tokens").get("ids")
        if not isinstance(prompt, list) or not prompt or any(type(x) is not int or x < 0 for x in prompt):
            problems.append("Missing/invalid prompt token IDs")
        elif expected_prompt_ids is not None and prompt != expected_prompt_ids:
            problems.append("Prompt tokens differ from independent tokenizer/chat reference")
    return problems


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True, help="New archive directory; never overwritten")
    parser.add_argument("--mode", choices=("runtime", "app"), required=True)
    parser.add_argument("--case", choices=tuple(CASES), default="qwen25-05b")
    parser.add_argument("--trials", type=int, default=3)
    parser.add_argument("--runs", type=int, default=3, help="Measured generations per fresh-process trial, plus one warmup")
    parser.add_argument("--tokens", type=int, default=32)
    parser.add_argument("--threads", type=int, help="Runtime mode only; default2")
    parser.add_argument("--model-relative-path", "--model", dest="model_path", help="Runtime path under the app's benchmark root")
    parser.add_argument("--no-memory-sampling", action="store_true", help="App mode only: disable PSS polling during generation; keep separate from sampled baselines")
    parser.add_argument("--reselect", choices=("reuse", "reload"), help="App mode only: reselect before generation; compare the same APK")
    parser.add_argument("--trace", action="store_true", help="Separate diagnostic pass; native-only Perfetto, no CPU sampling")
    parser.add_argument("--full-trace", action="store_true", help="Requires --trace: include framework/Dalvik categories; diagnostic only")
    parser.add_argument("--trace-processor", type=Path, default=ROOT / "build/tools/trace_processor")
    args = parser.parse_args(argv)
    if args.full_trace and not args.trace:
        parser.error("--full-trace requires --trace")
    if not 1 <= args.trials <= 10 or not 1 <= args.runs <= 10 or not 8 <= args.tokens <= 256:
        parser.error("Use trials/runs1..10 and tokens8..256")
    if args.mode == "app" and (args.threads is not None or args.model_path is not None):
        parser.error("--threads/--model-relative-path apply only to runtime mode")
    if args.mode != "app" and args.no_memory_sampling:
        parser.error("--no-memory-sampling applies only to app mode")
    if args.mode != "app" and args.reselect is not None:
        parser.error("--reselect applies only to app mode")
    if args.threads is not None and not 1 <= args.threads <= 8:
        parser.error("Use runtime threads1..8")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    archive = Archive(output)
    adb = ["adb", "-s", args.serial]
    metadata = {"schemaVersion": 1, "startedAtUtc": datetime.now(timezone.utc).isoformat(),
                "options": {key: str(value) if isinstance(value, Path) else value for key, value in vars(args).items()},
                "trials": [], "measurementValid": False, "diagnosticOnly": args.trace,
                "memorySampling": ("DISABLED" if args.no_memory_sampling else "APP_PSS_EVERY_100_MS") if args.mode == "app" else "RUNTIME_HARNESS_DEFAULT",
                "timingComparable": False, "cacheState": "FRESH_PROCESS_OS_CACHE_UNCONTROLLED",
                "screenTimeoutRestored": False, "preferencesUnchanged": False,
                "sourceArchiveScope": "Current sources before measuring an installed APK; not proof of its build inputs",
                "firstTokenScope": "FIRST_NONEMPTY_STREAM_CHUNK" if args.mode == "app" else "FIRST_GENERATED_TOKEN",
                "outputValidationScope": "NATURAL_EOG_NONEMPTY_SHA256" if args.mode == "app" else "NONEMPTY_TEXT_AND_PROMPT_TOKEN_REFERENCE",
                "notes": ["No storage-cache drop: this is process cold, not cold disk.",
                          "Runtime sky prompt and app math prompt intentionally differ; compare each mode only against itself.",
                          "App token counts are nonempty stream chunks, not native token IDs.",
                          "Repeated app load is a repository request, not UI reselection; it may reopen ORT."]}
    timeout_before = None
    prefs_before = None
    prefs_established = False

    def save(path, value):
        target = output / path
        target.write_text(json.dumps(value, indent=2) + "\n")

    def shell(*words, **kwargs):
        return archive.command(adb + ["shell", shlex.join(words)], **kwargs)[0]

    def preferences_hash():
        raw, code = archive.command(adb + ["shell", shlex.join(("run-as", PACKAGE, "sha256sum", "files/app.preferences_pb"))], check=False)
        if code == 0 and re.fullmatch(r"[0-9a-f]{64}\s+files/app\.preferences_pb", raw):
            return raw.split()[0]
        contents, code = archive.command(adb + ["shell", shlex.join(("run-as", PACKAGE, "ls", "files"))], check=False)
        if code != 0 or "app.preferences_pb" in contents.splitlines():
            raise RuntimeError("Cannot establish user preferences identity")
        return None

    def conditions(name):
        raw = {key: shell("dumpsys", service) for key, service in
               (("thermal", "thermalservice"), ("battery", "battery"), ("power", "power"))}
        raw["policy"] = shell("dumpsys", "window", "policy")
        raw["window"] = shell("dumpsys", "window")
        parsed = parse_conditions(**raw)
        save(name, {"parsed": parsed, "raw": raw})
        return parsed

    def require_unlocked(snapshot):
        if (snapshot["awake"] is not True or snapshot["keyguardShowing"] is not False
                or not snapshot["focusedWindow"]):
            raise RuntimeError("Device must remain awake and unlocked with a focused window")

    def require_untraced(name):
        query = archive.command(adb + ["exec-out", "perfetto", "--query-raw"], binary=True)
        (output / (name + "-perfetto-state.pb")).write_bytes(query)
        started = [v for f, w, v in proto_fields(query) if f == 4 and w == 0]
        sessions = [v for f, w, v in proto_fields(query) if f == 3 and w == 0]
        if len(sessions) != 1 or len(started) > 1 or (started and started[0] != 0):
            raise RuntimeError("Active/unknown Perfetto state: stop other captures before this pass")
        if any(line.strip() == "simpleperf" for line in shell("ps", "-A", "-o", "NAME").splitlines()):
            raise RuntimeError("A simpleperf process is active")

    save("run.json", metadata)
    try:
        directory = ROOT / "build/benchmarks" / CASES[args.case]
        manifest = json.loads((directory / "benchmark-manifest.json").read_text())
        references = json.loads((directory / "chat-reference.json").read_text())
        expected_prompt_ids = references[0]["ids"]
        save("model-manifest.json", manifest)
        expected_identity = pinned_files(manifest, directory)
        save("pinned-files.json", expected_identity)
        save("chat-reference.json", references)
        threads = args.threads or 2
        model_path = args.model_path or f"benchmarks/{CASES[args.case]}/{manifest['graph']}"
        path = PurePosixPath(model_path)
        if path.is_absolute() or ".." in path.parts or not model_path.endswith(".onnx"):
            raise ValueError("Runtime model must be a relative ONNX path under the benchmark root")
        metadata["runtimeModelRelativePath"] = model_path if args.mode == "runtime" else None
        metadata["runtimeThreads"] = threads if args.mode == "runtime" else None
        metadata["gitRevision"] = archive.command(["git", "rev-parse", "HEAD"])[0]
        metadata["gitStatus"] = archive.command(["git", "status", "--short"])[0]
        (output / "working-tree.patch").write_bytes(archive.command(["git", "diff", "HEAD", "--binary"], binary=True))
        archive_untracked_sources(ROOT, output, capture_phase=metadata["sourceArchiveScope"])
        metadata["untrackedSourcesManifest"] = "untracked-sources-manifest.json"
        metadata["driverSources"] = []
        # Keep an explicit copy of the driver even if it is already tracked.
        for source in sorted((ROOT / "benchmarks/onnx").glob("*.py")):
            destination = output / "sources" / source.relative_to(ROOT)
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
            metadata["driverSources"].append({"path": str(source.relative_to(ROOT)),
                "sha256": hashlib.sha256(destination.read_bytes()).hexdigest()})
        if archive.command(adb + ["get-state"])[0] != "device":
            raise RuntimeError("Device is not connected and authorized")
        if shell("getprop", "ro.kernel.qemu") == "1" or int(shell("getprop", "ro.build.version.sdk")) < 29:
            raise RuntimeError("Use a physical Android10+ device")
        metadata["deviceFingerprint"] = shell("getprop", "ro.build.fingerprint")
        paths = shell("pm", "path", PACKAGE).splitlines()
        if len(paths) != 1 or not paths[0].startswith("package:"):
            raise RuntimeError("Install a single debug APK first")
        apk_path = paths[0].removeprefix("package:")
        apk_sha = shell("sha256sum", apk_path).split()[0]
        if not re.fullmatch(r"[0-9a-f]{64}", apk_sha):
            raise RuntimeError("Invalid installed APK hash")
        metadata["installedApkSha256"] = apk_sha
        metadata["installedApkPath"] = apk_path
        prefs_before = preferences_hash()
        prefs_established = True
        metadata["preferencesSha256Before"] = prefs_before
        if shell("settings", "get", "global", "low_power") != "0":
            raise RuntimeError("Disable battery saver before measuring")
        require_unlocked(conditions("initial-conditions.json"))
        timeout_before = shell("settings", "get", "system", "screen_off_timeout")
        metadata["screenTimeoutBefore"] = timeout_before
        shell("settings", "put", "system", "screen_off_timeout", "1800000")
        if args.mode == "runtime":
            bundle = runtime_bundle_path(model_path, manifest["graph"])
            identities = []
            for expected in expected_identity:
                remote = str(bundle / expected["name"])
                size = int(shell("stat", "-c", "%s", remote))
                digest = shell("sha256sum", remote, timeout=120).split()[0]
                identity = {"name": expected["name"], "bytes": size, "sha256": digest}
                identities.append(identity)
                save("runtime-device-identity.json", identities)
                if identity != expected:
                    raise RuntimeError(f"Runtime device file differs from pinned artifact: {expected['name']}")
            metadata["runtimeIdentityVerified"] = True
            metadata["runtimeIdentityScope"] = "Full device bundle hashed once before trials; OS cache uncontrolled/warmed"
        print(output, flush=True)
        for index in range(1, args.trials + 1):
            name = f"trial-{index:02d}"
            trial_dir = output / name
            trial_dir.mkdir()
            trial = {"trial": index, "valid": False, "diagnosticOnly": args.trace,
                     "events": [], "failures": [], "startedAtUtc": datetime.now(timezone.utc).isoformat()}
            metadata["trials"].append(trial)
            save("run.json", metadata)
            try:
                shell("am", "force-stop", PACKAGE)
                deadline = time.monotonic() + 180
                attempt = 0
                while True:
                    snapshot = conditions(f"{name}/cooling-{attempt:02d}.json")
                    require_unlocked(snapshot)
                    if snapshot["thermalStatus"] is None or snapshot["batteryTemperatureC"] is None:
                        raise RuntimeError("Unknown thermal status/temperature")
                    if time.monotonic() >= deadline:
                        raise RuntimeError("Cooling did not reach thermal NORMAL and <=35C within180s")
                    if snapshot["thermalStatus"] == 0 and snapshot["batteryTemperatureC"] <= 35:
                        break
                    print(f"Cooling {name}: {snapshot['batteryTemperatureC']}C, thermal {snapshot['thermalStatus']}", flush=True)
                    time.sleep(min(5, max(0, deadline - time.monotonic())))
                    attempt += 1
                trial["before"] = snapshot
                require_untraced(name)
                component = "OnnxGgufBenchmark" if args.mode == "runtime" else "OnnxPerformanceValidation"
                extras = {"runs": str(args.runs), "tokens": str(args.tokens)}
                if args.mode == "runtime":
                    extras.update(model=model_path, threads=str(threads), provider="CPU", gpu="0")
                else:
                    extras["case"] = args.case
                    extras["memorySample"] = "0" if args.no_memory_sampling else "1"
                    if args.reselect is not None:
                        extras["reselect"] = args.reselect
                words = ["am", "instrument", "-w", "-r"]
                for key, value in extras.items():
                    words += ["-e", key, value]
                words.append(f"{PACKAGE}/{PACKAGE}.benchmark.{component}")
                command = adb + ["shell", shlex.join(words)]
                trial["command"] = command
                if args.trace:
                    capture_dir = trial_dir / "trace"
                    capture = [sys.executable, str(ROOT / "benchmarks/run.py"), "android", "perfetto",
                               "--serial", args.serial, "--package", PACKAGE, "--output", str(capture_dir),
                               "--no-cpu-sampling", *([] if args.full_trace else ["--native-only"]), "--seconds", "180",
                               "--trace-processor", str(args.trace_processor.resolve()), "--", *command]
                    _, capture_code = archive.command(capture, check=False, timeout=250)
                    raw = (capture_dir / "workload.txt").read_text()
                    capture_meta = json.loads((capture_dir / "run.json").read_text())
                    quality = json.loads((capture_dir / "capture.quality.json").read_text())
                    trial["traceQuality"] = quality
                    if capture_code != 0 or capture_meta.get("valid") is not True or quality.get("valid") is not True:
                        trial["failures"].append("Diagnostic capture/workload failed quality validation")
                    returncode = capture_meta.get("workloadExitCode")
                else:
                    raw, returncode = archive.command(command, check=False, timeout=600)
                (trial_dir / "instrumentation.txt").write_text(raw)
                trial["events"] = parse_events(raw, args.mode)
                trial["failures"] += validate_events(trial["events"], raw, returncode, command,
                    args.mode, args.case, args.runs, args.tokens, threads, model_path, expected_prompt_ids, manifest, args.reselect,
                    not args.no_memory_sampling if args.mode == "app" else None)
                trial["after"] = conditions(f"{name}/after-conditions.json")
                require_unlocked(trial["after"])
                if trial["after"]["thermalStatus"] != 0:
                    trial["failures"].append("Thermal status was not NORMAL after trial")
                trial["valid"] = not trial["failures"]
            except BaseException as error:
                trial["failures"].append(f"{type(error).__name__}: {error}")
                raise
            finally:
                trial["finishedAtUtc"] = datetime.now(timezone.utc).isoformat()
                save(f"{name}/trial.json", trial)
                save("run.json", metadata)
                shell("am", "force-stop", PACKAGE)
            if not trial["valid"]:
                raise RuntimeError(f"{name} failed: {trial['failures']}")
            print(f"{name}: VALID ({'diagnostic' if args.trace else 'timing'})", flush=True)
        if shell("sha256sum", apk_path).split()[0] != apk_sha:
            raise RuntimeError("Installed APK changed during the pass")
        metadata["measurementValid"] = True
    except BaseException as error:
        metadata["measurementValid"] = False
        metadata["failure"] = f"{type(error).__name__}: {error}"
        print(metadata["failure"], file=sys.stderr, flush=True)
    finally:
        cleanup_errors = []
        if timeout_before is not None:
            try:
                shell("settings", "delete", "system", "screen_off_timeout") if timeout_before == "null" else shell(
                    "settings", "put", "system", "screen_off_timeout", timeout_before)
                if shell("settings", "get", "system", "screen_off_timeout") != timeout_before:
                    raise RuntimeError("Screen timeout restoration readback differs")
                metadata["screenTimeoutRestored"] = True
            except Exception as error:
                cleanup_errors.append(str(error))
        if prefs_established:
            try:
                metadata["preferencesSha256After"] = preferences_hash()
                metadata["preferencesUnchanged"] = metadata["preferencesSha256After"] == prefs_before
                if not metadata["preferencesUnchanged"]:
                    cleanup_errors.append("App preferences changed during the pass")
            except Exception as error:
                cleanup_errors.append(str(error))
        metadata["cleanupErrors"] = cleanup_errors
        metadata["measurementValid"] = (metadata["measurementValid"] and not cleanup_errors
            and metadata["screenTimeoutRestored"] and metadata["preferencesUnchanged"]
            and len(metadata["trials"]) == args.trials and all(t["valid"] for t in metadata["trials"]))
        metadata["timingComparable"] = metadata["measurementValid"] and not args.trace
        metadata["finishedAtUtc"] = datetime.now(timezone.utc).isoformat()
        save("run.json", metadata)
    return 0 if metadata["measurementValid"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
