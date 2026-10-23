"""Offline command routing and consolidated workflow regressions."""
from contextlib import redirect_stdout, redirect_stderr
import io
import json
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import run as cli


class CommandLineTest(unittest.TestCase):
    def test_every_command_help_works_without_optional_packages(self):
        def leaves(routes, prefix=()):
            for name, value in routes.items():
                if isinstance(value, dict):
                    yield prefix + (name,)
                    yield from leaves(value, prefix + (name,))
                else:
                    yield prefix + (name,)
        for command in [(), *leaves(cli.COMMANDS)]:
            with self.subTest(command=command):
                result = subprocess.run([sys.executable, "-S", str(cli.ROOT / "run.py"), *command, "--help"],
                                        cwd=tempfile.gettempdir(), capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn("usage:", result.stdout)

    def test_forwards_host_workload_verbatim_and_preserves_failure_exit(self):
        flags = ["--serial", "device", "--", "adb", "shell", "am instrument -w -r app/Runner"]
        before_argv, before_path = sys.argv, sys.path[:]
        with patch.object(cli.runpy, "run_path", return_value={"perfetto_main": lambda argv: 7 if argv == flags else 99}):
            self.assertEqual(7, cli.main(["android", "perfetto", *flags]))
        self.assertIs(before_argv, sys.argv)
        self.assertEqual(before_path, sys.path)

    def test_unknown_or_missing_workload_does_not_launch_anything(self):
        with patch.object(cli.runpy, "run_path") as launch, redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            self.assertEqual(2, cli.main(["android"]))
            with self.assertRaises(SystemExit) as failure:
                cli.main(["android", "unknown"])
            self.assertEqual(2, failure.exception.code)
            launch.assert_not_called()

    def test_restore_uses_exact_pinned_manifests_and_stops_on_failure(self):
        restore = runpy.run_path(str(cli.ROOT / "onnx/models.py"))["restore_main"]
        manifest_path = sorted((cli.ROOT / "onnx/results/manifests").glob("*.json"))[0]
        manifest = json.loads(manifest_path.read_text())
        with patch.dict(restore.__globals__, download_main=lambda argv: calls.append(argv)):
            calls = []
            restore(["--only", manifest_path.stem, "--output", "fixture-root"])
        self.assertEqual([[manifest["repository"], manifest["graph"], str(Path("fixture-root") / manifest_path.stem),
                           "--revision", manifest["revision"]]], calls)
        with patch.dict(restore.__globals__, download_main=lambda argv: (_ for _ in ()).throw(ValueError("Hash mismatch"))):
            with self.assertRaisesRegex(ValueError, "Hash mismatch"):
                restore([])

    def test_matrix_preserves_configuration_order_and_stops_on_failure(self):
        matrix = runpy.run_path(str(cli.ROOT / "onnx/device.py"))["matrix_main"]
        calls = []
        def engine(argv):
            calls.append(argv)
            if len(calls) == 5:
                raise SystemExit(1)
        with patch.dict(matrix.__globals__, engine_main=engine), self.assertRaises(SystemExit):
            matrix(["--serial", "device", "--output", "results", "--max-temperature", "34"])
        configs = [(argv[argv.index("--mode") + 1], argv[argv.index("--threads") + 1],
                    argv[argv.index("--gpu") + 1]) for argv in calls[:4]]
        self.assertEqual([("onnx", "1", "0"), ("onnx", "2", "0"), ("gguf", "4", "0"), ("gguf", "4", "99")], configs)
        self.assertEqual(5, len(calls))
        self.assertTrue(all(argv[-1] == "34.0" for argv in calls))

    def test_android_and_iphone_summaries_keep_memory_units_separate(self):
        module = runpy.run_path(str(cli.ROOT / "onnx/summary.py"))
        with tempfile.TemporaryDirectory() as directory:
            for platform, memory in (("android", "PSS"), ("iphone", "RSS")):
                output = io.StringIO()
                with redirect_stdout(output):
                    module[platform + "_main"]([directory])
                self.assertIn("Peak " + memory + " MiB", output.getvalue())
            (Path(directory) / "EXCLUDED.txt").write_text("Incomplete capture")
            with self.assertRaisesRegex(ValueError, "Incomplete capture"):
                module["android_main"]([directory])


if __name__ == "__main__":
    unittest.main()
