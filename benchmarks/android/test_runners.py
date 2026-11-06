"""Offline benchmark regression checks; no device access."""
import hashlib
from pathlib import Path
import tempfile
import unittest
from source_archive import archive_untracked_sources, is_build_source
from journey_matrix import comparable_runtime_config, parse_runtime_config
import copy
from ui import validate_startup_metrics


class SourceArchiveTest(unittest.TestCase):
    def test_build_sources_include_shared_native_and_benchmark_inputs(self):
        for name in ("app/composeApp/src/commonMain/kotlin/core/performance/Trace.kt",
                     "plugin-llama/src/commonCpp/llama_native_trace.h",
                     "plugin-onnx/src/commonMain/OnnxRunner.kt",
                     "libraries/patches/llama.cpp/fix.patch", "benchmarks/android/ui.py", "benchmarks/run.py",
                     "benchmarks/onnx/performance_pass.py", "nativeEngine/src/nativeTest/scripts/check.py", "gradle/libs.versions.toml",
                     "app/androidApp/src/main/baseline-prof.txt"):
            self.assertTrue(is_build_source(Path(name)), name)

    def test_generated_private_and_non_source_paths_are_excluded(self):
        for name in ("app/composeApp/build/Foo.kt", "app/composeApp/src/generated/Foo.kt",
                     "benchmarks/onnx/results/debug.py", "benchmarks/android/results/debug.py", "benchmarks/android/.env",
                     "app/androidApp/src/main/keys.jks", "local.properties",
                     "app/androidApp/local.properties", "benchmarks/android/secrets/key.py",
                     "../outside.kt", "/outside.kt", "README.md"):
            self.assertFalse(is_build_source(Path(name)), name)

    def test_archive_preserves_bytes_hashes_and_rejects_symlink_parents(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repo"
            output = Path(directory) / "archive"
            root.mkdir(); output.mkdir()
            relative = Path("app/composeApp/src/commonMain/Trace.kt")
            source = root / relative
            source.parent.mkdir(parents=True)
            content = b"package example\n"
            source.write_bytes(content)
            private = Path(directory) / "private"
            private.mkdir()
            (private / "Leak.kt").write_text("not source")
            (source.parent / "linked").symlink_to(private, target_is_directory=True)
            (source.parent / "Link.kt").symlink_to(private / "Leak.kt")
            manifest = archive_untracked_sources(root, output, [relative, relative.parent / "linked/Leak.kt",
                                                                relative.parent / "Link.kt"])
            self.assertEqual(2, manifest["excludedCount"])
            self.assertEqual([{"path": relative.as_posix(), "bytes": len(content),
                               "sha256": hashlib.sha256(content).hexdigest()}], manifest["files"])
            self.assertEqual(content, (output / "untracked-sources" / relative).read_bytes())
            self.assertTrue((output / "untracked-sources-manifest.json").is_file())


class RuntimeConfigurationComparisonTest(unittest.TestCase):
    CPU = "t=4/4,b=256,ctx=4096,gpu=0,kv=1/1,fa=-1,kleidi=1,ub=64,offloadKqv=false,autoFit=false"
    VULKAN = "t=4/4,b=256,ctx=4096,gpu=37,kv=1/1,fa=-1,kleidi=1,ub=64,offloadKqv=true,autoFit=false"

    def test_matched_cpu_vulkan_backend_placement_and_offload_are_accepted(self):
        self.assertEqual(comparable_runtime_config(self.CPU), comparable_runtime_config(self.VULKAN))

    def test_context_batch_microbatch_and_kv_drift_remain_rejected(self):
        for field, value in (("ctx", "2048"), ("b", "128"), ("ub", "32"), ("kv", "8/8")):
            with self.subTest(field=field):
                fields = parse_runtime_config(self.VULKAN)
                fields[field] = value
                changed = ",".join(f"{key}={value}" for key, value in fields.items())
                self.assertNotEqual(comparable_runtime_config(self.CPU), comparable_runtime_config(changed))

    def test_no_other_config_fields_are_excluded(self):
        for field, value in (("t", "2/2"), ("fa", "1"), ("kleidi", "0"), ("autoFit", "true")):
            with self.subTest(field=field):
                fields = parse_runtime_config(self.VULKAN)
                fields[field] = value
                changed = ",".join(f"{key}={value}" for key, value in fields.items())
                self.assertNotEqual(comparable_runtime_config(self.CPU), comparable_runtime_config(changed))

    def test_field_order_does_not_create_false_mismatches(self):
        reordered = ",".join(reversed(self.VULKAN.split(",")))
        self.assertEqual(comparable_runtime_config(self.CPU), comparable_runtime_config(reordered))

    def test_missing_or_additional_shared_fields_cannot_silently_match(self):
        fields = parse_runtime_config(self.VULKAN)
        fields.pop("ub")
        missing = ",".join(f"{key}={value}" for key, value in fields.items())
        self.assertNotEqual(comparable_runtime_config(self.CPU), comparable_runtime_config(missing))
        self.assertNotEqual(comparable_runtime_config(self.CPU), comparable_runtime_config(self.VULKAN + ",futureSetting=true"))

    def test_malformed_or_duplicate_fields_cannot_match(self):
        for value in (None, "", self.CPU + ",ub=64", self.CPU + ",", "gpu==0"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                comparable_runtime_config(value)


class StartupMetricsTest(unittest.TestCase):
    def setUp(self):
        self.result = {
            "name": "coldBaselineProfile", "repeatIterations": 3,
            "metrics": {
                "timeToInitialDisplayMs": {"runs": [350.0, 370.0, 360.0]},
                "timeToFullDisplayMs": {"runs": [390.0, 420.0, 405.0]},
            },
        }

    def validate(self, result):
        return validate_startup_metrics([result], {result["name"]}, 3)

    def test_complete_cold_and_cache_preserving_samples_pass(self):
        for name in ("coldBaselineProfile", "processColdCachesPreserved"):
            result = dict(self.result, name=name)
            self.assertTrue(self.validate(result)["valid"])

    def test_ttid_only_or_summary_only_cannot_claim_usable_ready_timing(self):
        for missing in (None, {"median": 405.0}):
            result = copy.deepcopy(self.result)
            result["metrics"].pop("timeToFullDisplayMs")
            if missing is not None:
                result["metrics"]["timeToFullDisplayMs"] = missing
            self.assertFalse(self.validate(result)["valid"])

    def test_declared_iterations_do_not_hide_missing_or_extra_samples(self):
        for metric in self.result["metrics"]:
            for samples in ([400.0, 410.0], [400.0, 410.0, 420.0, 430.0]):
                result = copy.deepcopy(self.result)
                result["metrics"][metric]["runs"] = samples
                self.assertFalse(self.validate(result)["valid"])

    def test_null_nonfinite_and_nonpositive_timings_rejected(self):
        for invalid in (None, float("nan"), float("inf"), 0, -1, True, "400"):
            result = copy.deepcopy(self.result)
            result["metrics"]["timeToFullDisplayMs"]["runs"][1] = invalid
            self.assertFalse(self.validate(result)["valid"], invalid)

    def test_every_requested_startup_result_required(self):
        self.assertFalse(validate_startup_metrics([self.result], {"coldBaselineProfile", "coldNoCompilation"}, 3)["valid"])
        self.assertFalse(validate_startup_metrics([self.result, self.result], {"coldBaselineProfile"}, 3)["valid"])

    def test_scroll_results_do_not_require_startup_metrics(self):
        self.assertTrue(validate_startup_metrics([{"name": "streamingFling"}], {"streamingFling"}, 3)["valid"])


if __name__ == "__main__":
    unittest.main()
