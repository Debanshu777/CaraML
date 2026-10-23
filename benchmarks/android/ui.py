#!/usr/bin/env python3
"""Build, run, and archive a physical-device UI benchmark without touching the normal app."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from analysis import inspect_archive
from source_archive import archive_untracked_sources

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.debanshu777.caraml.benchmark"
STARTUP_TESTS = {"coldNoCompilation", "coldWarmupProfile", "coldBaselineProfile", "processColdCachesPreserved"}


def capture(*command):
    return subprocess.check_output(command, cwd=ROOT, text=True).strip()


def thermal_status(snapshot):
    match = re.search(r"^Thermal Status: (\d+)$", snapshot["thermal"], re.MULTILINE)
    return int(match.group(1)) if match else None


def battery_temperature(snapshot):
    match = re.search(r"temperature: (\d+)", snapshot["battery"])
    return int(match.group(1)) / 10 if match else None


def background_cpu_snapshot(adb):
    """Diagnostic only: two process CPU samples before any measured benchmark window."""
    command = [*adb, "shell", "top", "-b", "-n", "2", "-d", "1", "-m", "12"]
    snapshot = {"command": command, "startedAtUtc": datetime.now(timezone.utc).isoformat(),
                "scope": "pre-run endpoints only; not continuous workload monitoring",
                "failureThreshold": None, "collected": False}
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=6)
        snapshot.update(collected=result.returncode == 0, returnCode=result.returncode,
                        stdout=result.stdout, stderr=result.stderr)
    except (OSError, subprocess.TimeoutExpired) as error:
        snapshot["error"] = str(error)
    snapshot["finishedAtUtc"] = datetime.now(timezone.utc).isoformat()
    return snapshot


def validate_startup_metrics(results, expected_names, iterations):
    """Require usable-ready and first-frame timing for every requested startup launch."""
    reasons = []
    counts = {}
    for name in sorted(expected_names & STARTUP_TESTS):
        matching = [result for result in results if result.get("name") == name]
        if len(matching) != 1:
            reasons.append(f"{name}: expected exactly one startup result, found {len(matching)}")
            continue
        counts[name] = {}
        for metric in ("timeToInitialDisplayMs", "timeToFullDisplayMs"):
            runs = matching[0].get("metrics", {}).get(metric, {}).get("runs")
            counts[name][metric] = len(runs) if isinstance(runs, list) else 0
            if not isinstance(runs, list) or len(runs) != iterations:
                reasons.append(f"{name}.{metric}: expected {iterations} raw timings, found {counts[name][metric]}")
            elif any(type(value) not in (int, float) or not math.isfinite(value) or value <= 0 for value in runs):
                reasons.append(f"{name}.{metric}: timings must be finite positive numbers")
    return {"valid": not reasons, "reasons": reasons, "sampleCounts": counts}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Physical device from adb devices -l")
    parser.add_argument("--label", default="baseline", help="Archive label; letters, digits, - and _")
    parser.add_argument("--iterations", type=int, default=10)
    parser.add_argument("--max-start-temperature", type=float, default=35.0,
                        help="Maximum starting battery temperature in Celsius; match across comparisons")
    parser.add_argument("--full-tracing", action="store_true", help="Diagnostic composable traces; compare timing only with identically instrumented runs")
    parser.add_argument("--trace-processor", type=Path, default=ROOT / "build/tools/trace_processor",
                        help="Local official Perfetto processor; required for measurement quality gates")
    parser.add_argument("--scroll-window-ms", type=int, default=4000, help="Bounded new scroll journeys only, 2000..10000 ms")
    parser.add_argument("--message-count", type=int, default=200, help="New scroll journeys only, even number 200..2000")
    parser.add_argument("--stream-period-ms", type=int, default=100, help="Synthetic word update interval, 25..1000 ms")
    parser.add_argument("--refresh-rate", type=int, choices=[60, 90, 120],
                        help="Temporarily request min/peak Hz, restore afterward; verify physical display-mode endpoints")
    parser.add_argument("--soft-effects", choices=["on", "off"],
                        help="Opt-in visual A/B in the benchmark package only; leaves production defaults unchanged")
    parser.add_argument("--r8", action="store_true", help="Opt-in benchmark-only code/resource optimization experiment")
    parser.add_argument("--scenario", choices=["all", "startup", "startup-profile", "startup-baseline", "startup-cache-preserved",
                        "scroll", "scroll-short", "scroll-markdown", "scroll-streaming", "generate-profile"], default="all",
                        help="Use separate cooled runs for startup and scrolling comparisons")
    args = parser.parse_args(argv)
    if not re.fullmatch(r"[A-Za-z0-9_-]+", args.label) or not 1 <= args.iterations <= 100:
        parser.error("Use a simple label and 1..100 iterations")
    if not 20 <= args.max_start_temperature <= 40:
        parser.error("Starting temperature ceiling must be between 20 and 40 C")
    if not 2000 <= args.scroll_window_ms <= 10000 or not 200 <= args.message_count <= 2000 or args.message_count % 2:
        parser.error("Use a 2000..10000 ms window and an even message count from 200..2000")
    if not 25 <= args.stream_period_ms <= 1000:
        parser.error("Stream period must be between 25 and 1000 ms")
    if args.scenario == "generate-profile" and (args.soft_effects is not None or args.r8):
        parser.error("Generate profiles on the normal benchmark configuration; compare effects/R8 separately")
    processor = args.trace_processor.resolve()
    if args.scenario != "generate-profile" and (not processor.is_file() or not os.access(processor, os.X_OK)):
        parser.error("Install the official trace_processor locally first; see benchmarks/android/README.md")
    adb = ["adb", "-s", args.serial]

    def shell(*command):
        return capture(*adb, "shell", *command)

    if capture(*adb, "get-state") != "device":
        parser.error("The device must be connected and authorized")
    if shell("getprop", "ro.kernel.qemu") == "1" or int(shell("getprop", "ro.build.version.sdk")) < 31:
        parser.error("Use a physical Android 12+ device for frame-overrun measurements")
    generating_profile = args.scenario == "generate-profile"
    if generating_profile and int(shell("getprop", "ro.build.version.sdk")) < 33:
        parser.error("Profile generation requires an Android 13+ device without root")
    if "com.android.cli.interact.instrumentation" in shell("ps", "-A"):
        parser.error("Stop the Android CLI inspector before benchmarking: "
                     f"adb -s {args.serial} shell am force-stop com.android.cli.interact.instrumentation")

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    output = ROOT / "build/benchmark-results/android-ui" / f"{stamp}-{args.label}"
    output.mkdir(parents=True)
    env = dict(os.environ, ANDROID_SERIAL=args.serial)
    gradle = [str(ROOT / "gradlew"), "--console=plain"]
    if args.full_tracing:
        gradle.append("-PbenchmarkFullTracing=true")
    if args.r8:
        gradle.append("-PbenchmarkR8=true")
    metadata = {
        "schemaVersion": 2, "fixtureVersion": 3, "startedAtUtc": stamp,
        "fixtureTheme": "LIGHT", "fixtureBackdrop": "production FrostedPageScaffold only",
        "label": args.label, "serial": args.serial, "iterations": args.iterations,
        "scenario": args.scenario, "testsPassed": False,
        "fullTracing": args.full_tracing,
        "measurementValid": False,
        "scrollWindowMs": args.scroll_window_ms, "messageCount": args.message_count,
        "streamPeriodMs": args.stream_period_ms, "requestedRefreshHz": args.refresh_rate,
        "softEffectsOverride": args.soft_effects,
        "benchmarkR8Enabled": args.r8,
        "scrollWorkload": "legacy slow swipes" if args.scenario in ("all", "scroll") else args.scenario,
        "traceProcessorVersion": capture(str(processor), "--version") if not generating_profile else None,
        "maxStartTemperatureC": args.max_start_temperature,
        "gitRevision": capture("git", "rev-parse", "HEAD"),
        "gitStatus": capture("git", "status", "--short"),
        "submodules": capture("git", "submodule", "status"),
        "buildType": "benchmark (release-derived, non-debuggable, " +
                     ("R8 code/resource optimization experiment)" if args.r8 else "current release minification)"),
        "targetPackage": PACKAGE, "gradleCommand": gradle,
        "screenTimeoutBefore": shell("settings", "get", "system", "screen_off_timeout"),
        "refreshPolicyBefore": {key: shell("settings", "get", "system", key)
                                for key in ("min_refresh_rate", "peak_refresh_rate")},
    }
    app_profile = ROOT / "app/androidApp/src/main/baseline-prof.txt"
    metadata["appProfileSha256"] = hashlib.sha256(app_profile.read_bytes()).hexdigest() if app_profile.exists() else None

    def device_snapshot(name):
        snapshot = {
            "model": shell("getprop", "ro.product.model"),
            "fingerprint": shell("getprop", "ro.build.fingerprint"),
            "battery": shell("dumpsys", "battery"),
            "thermal": shell("dumpsys", "thermalservice"),
            "display": shell("dumpsys", "display"),
            "power": shell("dumpsys", "power"),
            "settings": {key: shell("settings", "get", scope, key) for scope, key in [
                ("global", "window_animation_scale"), ("global", "transition_animation_scale"),
                ("global", "animator_duration_scale"), ("global", "low_power"),
                ("system", "peak_refresh_rate"), ("system", "min_refresh_rate"),
                ("system", "screen_brightness"), ("system", "font_scale"),
                ("system", "screen_off_timeout"),
                ("system", "user_rotation"), ("system", "accelerometer_rotation"),
            ]},
        }
        (output / f"device-{name}.json").write_text(json.dumps(snapshot, indent=2) + "\n")
        return snapshot

    def run_gradle(log_name, tasks):
        command = gradle + tasks
        print("Running:", " ".join(command), flush=True)
        print("Log:", output / log_name, flush=True)
        with (output / log_name).open("w") as log:
            subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)

    try:
        # Installation can exceed a short user timeout before Macrobenchmark takes over.
        # This does not unlock a secure keyguard. Restore the setting even on test failure.
        shell("settings", "put", "system", "screen_off_timeout", "1800000")
        if args.refresh_rate is not None:
            for key in ("min_refresh_rate", "peak_refresh_rate"):
                shell("settings", "put", "system", key, str(args.refresh_rate))
        # Archive both tracked edits and new benchmark sources so a dirty-tree run is identifiable.
        (output / "working-tree.patch").write_text(capture("git", "diff", "HEAD", "--binary") + "\n")
        metadata["untrackedSourceArchive"] = archive_untracked_sources(ROOT, output)
        source_paths = ["benchmarks/android", "app/androidApp/src/benchmark", "gradle/libs.versions.toml",
                        "gradle.properties", "nativeEngine/gradle.properties", "app/androidApp/build.gradle.kts"]
        if (ROOT / "app/androidApp/src/main/baseline-prof.txt").exists():
            source_paths.append("app/androidApp/src/main/baseline-prof.txt")
        for relative in source_paths:
            source = ROOT / relative
            target = output / "sources" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            if source.is_dir():
                shutil.copytree(source, target, ignore=shutil.ignore_patterns("build", "__pycache__", "results"))
            else:
                shutil.copy2(source, target)
        run_gradle("build.log", [":app:androidApp:assembleBenchmark", ":benchmarks:android:assembleBenchmark"])
        apk = ROOT / "app/androidApp/build/outputs/apk/benchmark/androidApp-benchmark.apk"
        with apk.open("rb") as apk_file:
            metadata["apkSha256"] = hashlib.file_digest(apk_file, "sha256").hexdigest()
        if args.r8:
            mapping = ROOT / "app/androidApp/build/outputs/mapping/benchmark"
            if not mapping.is_dir() or not (mapping / "mapping.txt").is_file():
                raise RuntimeError("R8 was requested but no benchmark mapping was generated")
            shutil.copytree(mapping, output / "r8", dirs_exist_ok=True)
        subprocess.run(adb + ["install", "-r", str(apk)], check=True)
        # ONLY the isolated benchmark package is cleared. Normal CaraML data is untouched.
        if shell("pm", "clear", PACKAGE) != "Success":
            raise RuntimeError("Could not reset benchmark app data")
        # Record competing processes without running top during UI timing or
        # treating this brief observation as a new pass/fail condition.
        metadata["backgroundCpuBefore"] = background_cpu_snapshot(adb)
        before = device_snapshot("before")
        metadata["thermalStatusBefore"] = thermal_status(before)
        metadata["batteryTemperatureBeforeC"] = battery_temperature(before)
        if metadata["thermalStatusBefore"] != 0:
            raise RuntimeError("Device is thermally throttled; let it cool and rerun")
        if metadata["batteryTemperatureBeforeC"] is None or metadata["batteryTemperatureBeforeC"] > args.max_start_temperature:
            raise RuntimeError("Starting battery temperature is unknown or above the configured ceiling; let it cool and rerun")
        if before["settings"]["low_power"] == "1":
            raise RuntimeError("Turn off battery saver before measuring")
        if "mWakefulness=Awake" not in before["power"]:
            raise RuntimeError("Wake and unlock the device before measuring")
        started = time.time()
        try:
            test_arguments = [
                ":benchmarks:android:connectedBenchmarkAndroidTest",
                f"-Pandroid.testInstrumentationRunnerArguments.iterations={args.iterations}",
                f"-Pandroid.testInstrumentationRunnerArguments.scrollWindowMs={args.scroll_window_ms}",
                f"-Pandroid.testInstrumentationRunnerArguments.messageCount={args.message_count}",
                f"-Pandroid.testInstrumentationRunnerArguments.streamPeriodMs={args.stream_period_ms}",
            ]
            if args.full_tracing:
                test_arguments.append("-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.fullTracing.enable=true")
            if args.soft_effects is not None:
                test_arguments.append(f"-Pandroid.testInstrumentationRunnerArguments.softEffects={args.soft_effects}")
            # Use one class per filter: the connected-device runner can silently drop
            # repeated Class#method entries for the same class. Validate results below.
            if args.scenario != "all":
                test_class = {
                    "startup": "StartupBenchmark", "startup-profile": "StartupBenchmark",
                    "startup-baseline": "StartupBenchmark#coldBaselineProfile",
                    "startup-cache-preserved": "CachePreservingStartupBenchmark",
                    "scroll": "ChatScrollBenchmark", "generate-profile": "StartupProfileGenerator",
                    "scroll-short": "ShortChatScrollBenchmark#fastFling",
                    "scroll-markdown": "ShortChatScrollBenchmark#markdownFling",
                    "scroll-streaming": "ShortChatScrollBenchmark#streamingFling",
                }[args.scenario]
                test_arguments.append("-Pandroid.testInstrumentationRunnerArguments.class="
                                      f"com.debanshu777.caraml.benchmark.tests.{test_class}")
            if args.scenario == "startup-profile":
                test_arguments.append("-Pandroid.testInstrumentationRunnerArguments.notAnnotation="
                                      "com.debanshu777.caraml.benchmark.tests.WarmupComparison,"
                                      "com.debanshu777.caraml.benchmark.tests.MeasurementExperiment")
            if args.scenario in ("startup-cache-preserved", "scroll-short", "scroll-markdown", "scroll-streaming"):
                test_arguments.append("-Pandroid.testInstrumentationRunnerArguments.notAnnotation="
                                      "com.debanshu777.caraml.benchmark.tests.ProfileGeneration")
            if generating_profile:
                # Override the module's default generator exclusion; the selected
                # generator class has no warmup-comparison methods.
                test_arguments.append("-Pandroid.testInstrumentationRunnerArguments.notAnnotation="
                                      "com.debanshu777.caraml.benchmark.tests.WarmupComparison")
                test_arguments.append("-Pandroid.testInstrumentationRunnerArguments.generateProfile=true")
            run_gradle("benchmark.log", test_arguments)
            metadata["testsPassed"] = True
        finally:
            after = device_snapshot("after")
            metadata["thermalStatusAfter"] = thermal_status(after)
            metadata["batteryTemperatureAfterC"] = battery_temperature(after)
            metadata["thermalEndpointsValid"] = metadata["thermalStatusBefore"] == 0 and metadata["thermalStatusAfter"] == 0
            # Do not publish validity before scenario and trace-quality checks finish.
            metadata["measurementValid"] = None if generating_profile else False
            # Exclude stale artifacts from earlier runs, including after a failed test.
            additional = ROOT / "benchmarks/android/build/outputs/connected_android_test_additional_output"
            copied = []
            if additional.exists():
                for source in additional.rglob("*"):
                    if source.is_file() and source.stat().st_mtime >= started:
                        relative = source.relative_to(additional)
                        target = output / "results" / relative
                        target.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copy2(source, target)
                        copied.append(str(relative))
            metadata["resultFiles"] = copied
            test_results = ROOT / "benchmarks/android/build/outputs/androidTest-results"
            if test_results.exists():
                for source in test_results.rglob("*"):
                    if source.is_file() and source.stat().st_mtime >= started:
                        target = output / "test-results" / source.relative_to(test_results)
                        target.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copy2(source, target)
        if generating_profile:
            profiles = [output / "results" / path for path in metadata["resultFiles"]
                        if path.endswith("StartupProfileGenerator_startup-baseline-prof.txt")]
            if len(profiles) != 1 or not profiles[0].read_text().strip():
                raise RuntimeError("Expected one fresh nonempty startup Baseline Profile; inspect benchmark.log")
            rules = profiles[0].read_text()
            if "Lcom/debanshu777/caraml/MainActivity;" not in rules or "ChatScrollActivity" in rules:
                raise RuntimeError("Profile must cover MainActivity and exclude the synthetic scrolling activity")
            target = ROOT / "app/androidApp/src/main/baseline-prof.txt"
            shutil.copy2(profiles[0], target)
            metadata["generatedProfile"] = str(target.relative_to(ROOT))
            metadata["profileSha256"] = hashlib.sha256(target.read_bytes()).hexdigest()
            print("Generated:", target, flush=True)
        else:
            reports = [output / "results" / path for path in metadata["resultFiles"]
                       if path.endswith("benchmarkData.json")]
            if len(reports) != 1:
                raise RuntimeError("Expected one fresh Macrobenchmark JSON; inspect benchmark.log")
            results = json.loads(reports[0].read_text())["benchmarks"]
            expected = {
                "all": {"coldNoCompilation", "coldWarmupProfile", "coldBaselineProfile", "chatHistory"},
                "startup": {"coldNoCompilation", "coldWarmupProfile", "coldBaselineProfile"},
                "startup-profile": {"coldNoCompilation", "coldBaselineProfile"},
                "startup-baseline": {"coldBaselineProfile"},
                "startup-cache-preserved": {"processColdCachesPreserved"},
                "scroll": {"chatHistory"},
                "scroll-short": {"fastFling"}, "scroll-markdown": {"markdownFling"},
                "scroll-streaming": {"streamingFling"},
            }[args.scenario]
            actual = {result["name"] for result in results}
            metadata["scenarioComplete"] = actual == expected and all(
                result["repeatIterations"] == args.iterations for result in results)
            if not metadata["scenarioComplete"]:
                metadata["measurementValid"] = False
                raise RuntimeError(f"Incomplete benchmark: expected {sorted(expected)} with "
                                   f"{args.iterations} iterations each, found {sorted(actual)}")
            startup_metrics = validate_startup_metrics(results, expected, args.iterations)
            metadata["startupMetricQuality"] = startup_metrics
            if not startup_metrics["valid"]:
                raise RuntimeError("Startup TTID/TTFD gate failed: " + "; ".join(startup_metrics["reasons"]))
            quality = inspect_archive(output / "results", processor,
                                      expected_count=len(expected) * args.iterations,
                                      expected_refresh_hz=args.refresh_rate,
                                      stream_period_ms=args.stream_period_ms)
            (output / "trace-quality.json").write_text(json.dumps(quality, indent=2) + "\n")
            metadata["traceQualityValid"] = quality["valid"]
            metadata["invalidTraceCount"] = quality["invalidTraceCount"]
            metadata["measurementValid"] = (metadata["testsPassed"] and metadata["thermalEndpointsValid"]
                                            and metadata["scenarioComplete"] and startup_metrics["valid"] and quality["valid"])
            if not quality["valid"]:
                raise RuntimeError("Trace quality/refresh gate failed: " + "; ".join(quality["reasons"]) +
                                   ". See trace-quality.json; artifacts preserved, measurements are exploratory.")
        if not generating_profile and not metadata["measurementValid"]:
            raise RuntimeError("Tests passed, but the device warmed during measurement. Results are exploratory; "
                               "cool the device and rerun with --scenario startup or --scenario scroll.")
        metadata["status"] = "passed"
    except BaseException as error:
        metadata["measurementValid"] = None if generating_profile else False
        metadata["status"] = "failed"
        metadata["error"] = str(error)
        raise
    finally:
        cleanup_errors = []
        if args.refresh_rate is not None:
            refresh_errors = []
            for key, value in metadata["refreshPolicyBefore"].items():
                try:
                    if value == "null":
                        shell("settings", "delete", "system", key)
                    else:
                        shell("settings", "put", "system", key, value)
                    if shell("settings", "get", "system", key) != value:
                        raise RuntimeError(f"Restored {key} did not match its original value")
                except Exception as error:
                    refresh_errors.append(f"{key}: {error}")
            metadata["refreshPolicyRestored"] = not refresh_errors
            if refresh_errors:
                metadata["refreshPolicyRestoreError"] = "; ".join(refresh_errors)
                cleanup_errors.append("Refresh policy restoration failed: " + "; ".join(refresh_errors))
                print("Could not restore refresh policy:", "; ".join(refresh_errors), flush=True)
        try:
            original_timeout = metadata["screenTimeoutBefore"]
            if original_timeout == "null":
                shell("settings", "delete", "system", "screen_off_timeout")
            else:
                shell("settings", "put", "system", "screen_off_timeout", original_timeout)
            if shell("settings", "get", "system", "screen_off_timeout") != original_timeout:
                raise RuntimeError("Restored screen timeout did not match its original value")
            metadata["screenTimeoutRestored"] = True
        except Exception as error:
            metadata["screenTimeoutRestored"] = False
            metadata["screenTimeoutRestoreError"] = str(error)
            cleanup_errors.append(f"Screen timeout restoration failed: {error}")
            print("Could not restore screen timeout:", error, flush=True)
        if cleanup_errors:
            metadata["cleanupErrors"] = cleanup_errors
            metadata["measurementValid"] = None if generating_profile else False
            metadata["status"] = "failed"
        metadata["finishedAtUtc"] = datetime.now(timezone.utc).isoformat()
        (output / "run.json").write_text(json.dumps(metadata, indent=2) + "\n")
        print("Archive:", output, flush=True)
        if cleanup_errors and sys.exc_info()[0] is None:
            raise RuntimeError("; ".join(cleanup_errors))


if __name__ == "__main__":
    main()
