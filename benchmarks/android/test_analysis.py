"""Offline benchmark regression checks; no device access."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from analysis import display_mode_endpoints, inspect_archive, inspect_trace
from analysis import summarize_vulkan as summarize


class TraceQualityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.archive = Path(self.temporary.name)
        self.trace = self.archive / "ShortChatScrollBenchmark_streamingFling_iter000.perfetto-trace"
        self.trace.write_bytes(b"fixture")
        self.metrics = dict(errors=[], durationMs=4500, measurementWindowMs=4000,
                            frameCount=100, frameBudgetMsMedian=16.6,
                            callbackIntervalMsMedian=8.3, streamUpdateCount=39,
                            streamUpdateSpanMs=3900)

    def mode(self, before=120, after=120):
        for endpoint, rate in (("before", before), ("after", after)):
            display = f"mActiveSfDisplayMode=DisplayMode{{id=3, peakRefreshRate={rate}, vsyncRate={rate}}}"
            (self.archive / f"device-{endpoint}.json").write_text(json.dumps({"display": display}))

    def test_app_deadline_can_span_two_display_periods(self):
        self.mode()
        with patch("analysis.query_json", return_value=self.metrics):
            result = inspect_archive(self.archive, "processor", expected_count=1, expected_refresh_hz=120)
        self.assertTrue(result["valid"], result)
        self.assertIn("not continuous", result["refreshValidation"]["method"])

    def test_missing_or_wrong_endpoint_mode_rejected(self):
        self.assertFalse(display_mode_endpoints(self.archive, 120)["valid"])
        self.mode(after=60)
        with patch("analysis.query_json", return_value=self.metrics):
            self.assertFalse(inspect_archive(self.archive, "processor", expected_refresh_hz=120)["valid"])

    def test_one_update_or_short_burst_is_not_sustained_streaming(self):
        for count, span in ((1, 0), (39, 300), (2, 3900)):
            metrics = dict(self.metrics, streamUpdateCount=count, streamUpdateSpanMs=span)
            with self.subTest(count=count, span=span), patch("analysis.query_json", return_value=metrics):
                self.assertFalse(inspect_trace("processor", self.trace)["valid"])

    def test_archived_nondefault_stream_period_used(self):
        (self.archive / "run.json").write_text(json.dumps({"streamPeriodMs": 1000}))
        metrics = dict(self.metrics, streamUpdateCount=3, streamUpdateSpanMs=3000)
        with patch("analysis.query_json", return_value=metrics):
            result = inspect_archive(self.archive, "processor")
        self.assertTrue(result["valid"], result)
        self.assertEqual(1000, result["traces"][0]["streamValidation"]["periodMs"])

    def test_data_loss_and_missing_iteration_rejected(self):
        metrics = dict(self.metrics, errors=[{"name": "ftrace_cpu_overrun", "value": 1}])
        with patch("analysis.query_json", return_value=metrics):
            self.assertFalse(inspect_archive(self.archive, "processor")["valid"])
        with patch("analysis.query_json", return_value=self.metrics):
            self.assertFalse(inspect_archive(self.archive, "processor", expected_count=2)["valid"])

    def test_empty_or_unparseable_trace_rejected(self):
        with patch("analysis.query_json", side_effect=ValueError("corrupt trace")):
            self.assertFalse(inspect_trace("processor", self.trace)["valid"])
        self.trace.write_bytes(b"")
        self.assertFalse(inspect_trace("processor", self.trace)["valid"])


class VulkanTimingSummaryTest(unittest.TestCase):
    group = "Vulkan Timings:\nMUL_MAT q4_K m=100 n=8 k=256: 2 x 5 us = 10 us (0.01 GFLOPS/s)\nFLASH_ATTN_EXT dst(1,2,3,4): 1 x 3 us = 3 us\nTotal time: 13 us.\n"

    def test_preserves_group_boundaries_and_sums_matching_operations(self):
        result = summarize(self.group + self.group)
        self.assertEqual(result["groups"], 2)
        self.assertEqual(result["totalGpuUs"], 26)
        self.assertEqual(result["operations"][0]["count"], 4)
        self.assertEqual(result["operations"][0]["totalUs"], 20)

    def test_rejects_incomplete_or_inconsistent_output(self):
        for text in ("", self.group.removesuffix("Total time: 13 us.\n"), self.group.replace("13 us.", "100 us.")):
            with self.assertRaises(ValueError):
                summarize(text)


if __name__ == "__main__":
    unittest.main()
