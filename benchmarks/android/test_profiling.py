"""Offline benchmark regression checks; no device access."""
import unittest
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from profiling import data_source_names, make_config, proto_fields
from profiling import require_raw_instrumentation, validate_workload


def blob(field, payload):
    assert field < 16 and len(payload) < 128
    return bytes([field * 8 + 2, len(payload)]) + payload


class NativeCaptureTest(unittest.TestCase):
    def test_reads_registered_sources_and_skips_unrelated_producers(self):
        # Producer.name is not a registered data source; duplicate sources are
        # legal when several processes register the same descriptor.
        source = blob(2, blob(1, blob(1, b"linux.perf")))
        query = blob(1, blob(2, b"gpu.renderstages")) + source + source + bytes([24, 1])
        self.assertEqual(data_source_names(query), {"linux.perf"})

    def test_rejects_truncated_probe_instead_of_silently_enabling_sources(self):
        with self.assertRaises(ValueError):
            list(proto_fields(bytes([18, 10, 1])))

    def test_missing_cpu_profiler_fails_instead_of_claiming_callstack_capture(self):
        sources = {"linux.ftrace", "linux.process_stats", "linux.sys_stats"}
        with self.assertRaisesRegex(ValueError, "linux.perf"):
            make_config("com.example.app", sources, 30, 99)
        config = make_config("com.example.app", sources, 30, 99, cpu_sampling=False)
        self.assertNotIn('name: "linux.perf"', config)

    def test_gpu_memory_is_not_treated_as_gpu_execution_support(self):
        sources = {"linux.ftrace", "linux.process_stats", "linux.sys_stats", "linux.perf", "android.gpu.memory"}
        config = make_config("com.example.app", sources, 30, 99)
        self.assertIn('name: "android.gpu.memory"', config)
        self.assertNotIn('name: "gpu.renderstages"', config)
        self.assertIn("user_frames: UNWIND_DWARF", config)
        with self.assertRaisesRegex(ValueError, "gpu.counters"):
            make_config("com.example.app", sources, 30, 99, gpu_counter_ids=[1])

    def test_native_only_excludes_framework_sources_and_keeps_native_evidence(self):
        sources = {"linux.ftrace", "linux.process_stats", "linux.sys_stats", "linux.perf",
                   "android.surfaceflinger.frametimeline", "android.gpu.memory", "gpu.renderstages"}
        full = make_config("com.example.app", sources, 30, 99)
        native = make_config("com.example.app", sources, 30, 99, native_only=True)
        for category in ("am", "wm", "gfx", "view", "dalvik"):
            self.assertIn(f'atrace_categories: "{category}"', full)
        self.assertIn('name: "android.surfaceflinger.frametimeline"', full)
        self.assertNotIn("atrace_categories:", native)
        self.assertNotIn("android.surfaceflinger.frametimeline", native)
        for retained in ('atrace_apps: "com.example.app"', 'sched/sched_switch',
                         'power/cpu_frequency', 'name: "linux.process_stats"',
                         'name: "linux.sys_stats"', 'name: "linux.perf"',
                         'name: "android.gpu.memory"', 'name: "gpu.renderstages"'):
            self.assertIn(retained, native)
        no_samples = make_config("com.example.app", sources, 30, 99,
                                 cpu_sampling=False, native_only=True)
        self.assertNotIn('name: "linux.perf"', no_samples)

    def test_cli_metadata_labels_native_only_and_default_profiles(self):
        sources = (b"linux.ftrace", b"linux.process_stats", b"linux.sys_stats", b"linux.perf")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            query = root / "query.pb"
            query.write_bytes(b"".join(blob(2, blob(1, blob(1, source))) for source in sources))
            for profile, flags in (("native-only", ["--native-only"]), ("full", [])):
                output = root / profile
                result = subprocess.run(
                    [sys.executable, str(Path(__file__).resolve().parents[1] / "run.py"), "android", "perfetto",
                     "--config-only", "--query-file", str(query), "--output", str(output), *flags],
                    capture_output=True, text=True,
                )
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(json.loads((output / "capabilities.json").read_text())["captureProfile"],
                                 profile)


class CaptureWorkloadTest(unittest.TestCase):
    command = ["adb", "-s", "device", "shell", "am", "instrument", "-w", "-r", "app/Runner"]
    passed = 'INSTRUMENTATION_STATUS: stream={"case":"model","stage":"outcome","result":"PASS"}\nINSTRUMENTATION_CODE: -1\n'

    def test_requires_raw_waiting_instrumentation_before_recording(self):
        with self.assertRaises(ValueError):
            require_raw_instrumentation(["adb", "shell", "am instrument -w app/Runner"])
        require_raw_instrumentation(self.command)

    def test_accepts_complete_harness_and_junit_success(self):
        self.assertTrue(validate_workload(self.command, 0, self.passed)["valid"])
        self.assertTrue(validate_workload(self.command, 0, "OK (3 tests)\nINSTRUMENTATION_CODE: -1\n")["valid"])

    def test_rejects_exit_zero_without_instrumentation_completion(self):
        self.assertFalse(validate_workload(self.command, 0, self.passed.split("INSTRUMENTATION_CODE")[0])["valid"])
        self.assertFalse(validate_workload(self.command, 0, "INSTRUMENTATION_CODE: -1\n")["valid"])

    def test_rejects_failed_skipped_and_crashed_cases(self):
        for result in ("FAILED", "CONDITIONED_SKIP", "TIMEOUT"):
            self.assertFalse(validate_workload(self.command, 0, self.passed.replace('"PASS"', f'"{result}"'))["valid"])
        self.assertFalse(validate_workload(self.command, 0, self.passed + "INSTRUMENTATION_FAILED: crashed\n")["valid"])
        self.assertFalse(validate_workload(self.command, 0, self.passed + 'INSTRUMENTATION_STATUS: stream={"stage":"failure"}\n')["valid"])

    def test_generic_commands_use_exit_code(self):
        self.assertTrue(validate_workload(["python3", "workload.py"], 0, "")["valid"])
        self.assertFalse(validate_workload(["python3", "workload.py"], 1, "")["valid"])


if __name__ == "__main__":
    unittest.main()
