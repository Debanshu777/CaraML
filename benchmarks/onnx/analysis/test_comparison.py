"""Offline guards against publishing partial or unmatched ONNX comparisons."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

from comparison import compare, summarize


class ComparisonValidityTest(unittest.TestCase):
    def test_partial_or_invalid_run_never_produces_summary(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            self.assertEqual(summarize(folder)['status'], 'not_captured')
            (folder / 'run.json').write_text('{"trials":')
            self.assertEqual(summarize(folder)['status'], 'in_progress')
            (folder / 'run.json').write_text(json.dumps({'measurementValid': True}))
            self.assertEqual(summarize(folder)['status'], 'in_progress')
            for values in ({'measurementValid': False},
                           {'measurementValid': True, 'timingComparable': False, 'diagnosticOnly': True}):
                (folder / 'run.json').write_text(json.dumps({'finishedAtUtc': 'finished', **values}))
                result = summarize(folder)
                self.assertEqual(result['status'], 'excluded')
                self.assertNotIn('summary', result)

    def test_same_apk_workload_and_output_are_required_for_paired_claim(self):
        before = {'status': 'complete_valid', 'case': 'qwen25-05b', 'pinnedFiles': [],
                  'runtimeConfigs': ['CPU'], 'installedApkSha256': 'a', 'memorySampling': True,
                  'options': {'trials': 3, 'runs': 3, 'tokens': 32},
                  'outputTexts': ['2 plus 3 is 5.'], 'outputHashes': ['text-hash'],
                  'summary': {'whole_load_ms': {'median': 1000}}}
        after = copy.deepcopy(before)
        self.assertEqual(compare(before, after, ('whole_load_ms',), same_apk=True)['status'], 'complete_valid')
        after['installedApkSha256'] = 'b'
        self.assertEqual(compare(before, after, ('whole_load_ms',), same_apk=True)['status'], 'excluded_unmatched_apk')
        after = copy.deepcopy(before)
        after['options']['runs'] = 1
        self.assertEqual(compare(before, after, ('whole_load_ms',))['status'], 'excluded_unmatched_workload')
        after = copy.deepcopy(before)
        after['diagnosticOnly'] = True
        self.assertEqual(compare(before, after, ('whole_load_ms',))['status'], 'excluded_diagnostic')
        after = copy.deepcopy(before)
        after['outputTexts'] = ['Different answer']
        after['outputHashes'] = ['different-hash']
        self.assertEqual(compare(before, after, ('whole_load_ms',))['status'], 'excluded_unmatched_output')


if __name__ == '__main__':
    unittest.main()
