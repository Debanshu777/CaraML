import unittest

from run_android import process_exit_reason
from run_matrix import parse_thermal


class ProcessExitTest(unittest.TestCase):
    def test_matches_process_instead_of_reusing_another_trials_exit(self):
        report = """ApplicationExitInfo #0:
          timestamp=2026-10-25 pid=1234 realUid=10000
          process=com.example reason=10 (USER REQUESTED) status=0
        ApplicationExitInfo #1:
          timestamp=2026-10-25 pid=123 realUid=10000
          process=com.example reason=3 (LOW_MEMORY) status=0
        """
        self.assertEqual({"code": 3, "name": "LOW_MEMORY"}, process_exit_reason(report, "123"))
        self.assertIsNone(process_exit_reason(report, "12"))

    def test_missing_exit_evidence_remains_unknown(self):
        self.assertIsNone(process_exit_reason("No exits recorded", "123"))


def snapshot(cpu=40, gpu=39, skin=37, cooling=0):
    return f"""Thermal Status: 1
Cached temperatures:
    Temperature{{mValue=20.0, mType=0, mName=BIG, mStatus=0}}
Current temperatures from HAL:
    Temperature{{mValue={cpu}, mType=0, mName=BIG, mStatus=0}}
    Temperature{{mValue={gpu}, mType=1, mName=G3D, mStatus=0}}
    Temperature{{mValue={skin}, mType=3, mName=VIRTUAL-SKIN, mStatus=1}}
Current cooling devices from HAL:
    CoolingDevice{{mValue={cooling}, mType=2, mName=thermal-cpufreq-2}}
    CoolingDevice{{mValue=10, mType=1, mName=chg_mdis}}
Temperature static thresholds from HAL:
"""


class ThermalReadinessTest(unittest.TestCase):
    def test_light_skin_status_does_not_imply_active_compute_cooling(self):
        state = parse_thermal(snapshot())
        self.assertTrue(state["ready"])
        self.assertEqual(40, state["temperatures_c"]["BIG"])
        self.assertNotIn("chg_mdis", state["compute_cooling"])

    def test_cached_cold_reading_does_not_hide_current_heat_or_cooling(self):
        for text in [snapshot(cpu=56), snapshot(gpu=51), snapshot(skin=40), snapshot(cooling=2)]:
            self.assertFalse(parse_thermal(text)["ready"])

    def test_missing_hardware_evidence_is_not_ready(self):
        self.assertFalse(parse_thermal("Thermal Status: 0")["ready"])


if __name__ == "__main__":
    unittest.main()
