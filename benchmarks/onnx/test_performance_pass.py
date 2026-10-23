import hashlib
import io
from contextlib import redirect_stderr
import json
from pathlib import Path
import shlex
import tempfile
import unittest
from unittest.mock import patch

import performance_pass as perf

COMMAND = ['adb', '-s', 'example', 'shell', 'am instrument -w -r example/Benchmark']
DIGEST = 'a' * 64
MODEL = 'benchmarks/qwen25-05b-onnx/onnx/model_int8.onnx'
MANIFEST = {'graph': 'onnx/model_int8.onnx', 'files': [
    {'rfilename': 'onnx/model_int8.onnx', 'size': 12, 'lfs': {'sha256': DIGEST}}]}


def fixture(mode='app'):
    rows = [dict(stage='environment', foreground_resumed=True, foreground_focused=True,
                 display_interactive=True, thermal=0, memory_sampling=True),
            dict(stage='load', load_ms=3000, runtime='ONNX Runtime 1.31.0; CPU; 2 threads',
                 thermal=0, thermal_start=0, thermal_end=0, model=MODEL, context=2048,
                 foreground_verified=True, prepare_ms=100, resolve_ms=10, whole_load_ms=3120)]
    for i in range(2):
        text = '2 plus 3 is 5.'
        rows.append(dict(stage='generation', iteration=i, warmup=i == 0, tokens=8, text=text,
                         ttft_ms=200, elapsed_ms=1000, foreground_verified=True, thermal_start=0,
                         thermal_end=0, token_count_scope='NONEMPTY_STREAM_CHUNKS', stop_reason=1,
                         output_valid=True, output_sha256=hashlib.sha256(text.encode()).hexdigest(),
                         model=MODEL, decode_tokens_per_second=9, end_to_end_tokens_per_second=8))
    if mode == 'app':
        rows += [dict(stage='plan', context=2048, backend='CPU', key_cache='F32', value_cache='F32'),
                 dict(stage='identity', files=[dict(name='onnx/model_int8.onnx', bytes=12, sha256=DIGEST)]),
                 dict(stage='repeat_load', load_ms=2800, runtime=rows[1]['runtime'], context=2048,
                      foreground_verified=True, thermal_start=0, thermal_end=0, scope='REPOSITORY_REQUEST'),
                 dict(stage='complete', success=True)]
    else:
        rows.append(dict(stage='prompt_tokens', ids=[1, 2, 3]))
    rows.append(dict(stage='outcome', result='PASS'))
    for row in rows:
        if mode == 'app':
            row['case'] = 'qwen25-05b'
        else:
            row['event'] = row['stage']
    return rows


def raw(rows, mode='app', code=-1):
    prefix = 'CARAML_ONNX_PERF ' if mode == 'app' else 'CARAML_BENCH '
    return ''.join('INSTRUMENTATION_STATUS: stream=' + prefix + json.dumps(r) + '\n' for r in rows) + f'INSTRUMENTATION_CODE: {code}\n'


def validate(rows, mode='app', **kwargs):
    return perf.validate_events(rows, raw(rows, mode), 0, COMMAND, mode, 'qwen25-05b', 1, 32,
                                model_path=MODEL, expected_prompt_ids=[1, 2, 3], manifest=MANIFEST, **kwargs)


class ValidationTest(unittest.TestCase):
    def test_valid_runtime_and_app(self):
        for mode in ('app', 'runtime'):
            rows = fixture(mode)
            self.assertEqual(rows, perf.parse_events(raw(rows, mode), mode))
            self.assertEqual([], validate(rows, mode))

    def test_missing_duplicate_or_error_records_fail(self):
        for mode in ('app', 'runtime'):
            rows = fixture(mode)
            for changed in (rows[1:], rows + [rows[1]], rows + [dict(stage='error', event='error')],
                            [r for r in rows if r['stage'] != 'generation']):
                with self.subTest(mode=mode, changed=changed):
                    self.assertTrue(validate(changed, mode))

    def test_invalid_generation_data_and_cancelled_output_fail(self):
        mutations = [('ttft_ms', float('nan')), ('elapsed_ms', 10), ('thermal_start', 1),
                     ('thermal_end', None), ('foreground_verified', False), ('warmup', False),
                     ('tokens', 32), ('stop_reason', 4), ('stop_reason', True), ('output_valid', False),
                     ('output_sha256', 'b' * 64), ('text', ' '), ('iteration', 3), ('iteration', False)]
        for field, value in mutations:
            rows = fixture()
            rows[2][field] = value
            with self.subTest(field=field):
                self.assertTrue(validate(rows))

    def test_identity_and_plan_mutations_fail(self):
        for field, value in [('sha256', 'b' * 64), ('bytes', 13), ('name', 'other.onnx')]:
            rows = fixture()
            next(r for r in rows if r['stage'] == 'identity')['files'][0][field] = value
            self.assertTrue(validate(rows))
        for field, value in [('context', 4096), ('backend', 'VULKAN'), ('key_cache', 'Q8_0')]:
            rows = fixture()
            next(r for r in rows if r['stage'] == 'plan')[field] = value
            self.assertTrue(validate(rows))

    def test_reselection_requires_actual_requested_path_before_generation(self):
        for mode in ('reuse', 'reload'):
            rows = fixture()
            selection = dict(stage='reselect', case='qwen25-05b', mode=mode, reused=mode == 'reuse',
                             elapsed_ms=10, context=2048, runtime=rows[1]['runtime'], thermal_start=0,
                             thermal_end=0, foreground_verified=True, scope='PREPARE_AND_REPOSITORY_RESELECT')
            rows.insert(2, selection)
            self.assertEqual([], validate(rows, reselect=mode))
            self.assertTrue(validate(rows))
            selection['reused'] = not selection['reused']
            self.assertTrue(validate(rows, reselect=mode))
            selection['reused'] = mode == 'reuse'
            rows.remove(selection); rows.append(selection)
            self.assertTrue(validate(rows, reselect=mode))
        self.assertTrue(validate(fixture(), reselect='reuse'))

    def test_memory_sampling_mode_must_match_explicit_request(self):
        rows = fixture()
        self.assertEqual([], validate(rows, memory_sampling=True))
        self.assertTrue(validate(rows, memory_sampling=False))
        rows[0]['memory_sampling'] = False
        self.assertEqual([], validate(rows, memory_sampling=False))
        for value in (None, 0, 'false'):
            rows[0]['memory_sampling'] = value
            self.assertTrue(validate(rows, memory_sampling=False))
        del rows[0]['memory_sampling']
        self.assertTrue(validate(rows, memory_sampling=True))

    def test_raw_completion_must_be_explicit_and_unique(self):
        rows = fixture()
        for output, code in [(raw(rows, code=0), 0), (raw(rows) + 'INSTRUMENTATION_CODE: -1\n', 0),
                             (raw(rows).replace('INSTRUMENTATION_CODE: -1', ''), 0), (raw(rows), 1)]:
            self.assertTrue(perf.validate_events(rows, output, code, COMMAND, 'app', 'qwen25-05b', 1, 32))

    def test_runtime_prompt_and_provider_must_match(self):
        rows = fixture('runtime')
        next(r for r in rows if r['event'] == 'prompt_tokens')['ids'] = [1, 2, 4]
        self.assertTrue(validate(rows, 'runtime'))
        rows = fixture('runtime'); rows[1]['runtime'] += ' (fallback)'
        self.assertTrue(validate(rows, 'runtime'))

    def test_duplicate_json_fields_are_not_silently_overwritten(self):
        for line in ('{"stage":"error","stage":"outcome"}', '{"stage":"load","load_ms":1,"load_ms":2}', '[]'):
            with self.assertRaises(ValueError):
                perf.parse_events('CARAML_ONNX_PERF ' + line, 'app')

    def test_pins_require_exact_local_git_blob_and_confined_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp); content = b'{}'; (directory / 'model.onnx').write_bytes(content)
            entry = dict(rfilename='model.onnx', size=2, blobId=hashlib.sha1(b'blob 2\0' + content).hexdigest())
            manifest = dict(graph='model.onnx', files=[entry])
            expected = perf.pinned_files(manifest, directory)
            self.assertEqual(hashlib.sha256(content).hexdigest(), expected[0]['sha256'])
            (directory / 'model.onnx').write_bytes(b'[]')
            with self.assertRaisesRegex(ValueError, 'Git blob'):
                perf.pinned_files(manifest, directory)
        for name in ('../model.onnx', '/model.onnx', 'some/other.onnx'):
            with self.assertRaises(ValueError):
                perf.runtime_bundle_path(name, 'onnx/model_int8.onnx')


class DriverFailureTest(unittest.TestCase):
    def run_fake(self, root, output, cleanup_failure=False, interrupted=False):
        directory = root / 'build/benchmarks/qwen25-05b-onnx'
        (directory / 'onnx').mkdir(parents=True)
        (directory / 'onnx/model_int8.onnx').write_bytes(b'x' * 12)
        (directory / 'benchmark-manifest.json').write_text(json.dumps(MANIFEST))
        (directory / 'chat-reference.json').write_text('[{"ids":[1,2,3]}]')
        class FakeArchive:
            timeout = '30000'
            def __init__(self, output): pass
            def command(self, cmd, **kwargs):
                if kwargs.get('binary'):
                    return b'\x18\x00\x20\x00' if 'exec-out' in cmd else b''
                if cmd[0] == 'git': return ('example', 0)
                if cmd[-1] == 'get-state': return ('device', 0)
                words = shlex.split(cmd[-1])
                result = ''
                if words[:2] == ['am', 'instrument']:
                    if interrupted: raise KeyboardInterrupt('test interrupt')
                    return (raw(fixture()), 0)
                if words[:2] == ['settings', 'put']:
                    if cleanup_failure and words[-1] == '30000': raise RuntimeError('restore failed')
                    self.timeout = words[-1]
                elif words[:2] == ['settings', 'get']:
                    result = self.timeout if words[-1] == 'screen_off_timeout' else '0'
                elif words[0] == 'getprop': result = '36' if words[-1].endswith('.sdk') else '0'
                elif words[:2] == ['pm', 'path']: result = 'package:/data/app/base.apk'
                elif words[0] == 'sha256sum': result = DIGEST + '  /data/app/base.apk'
                elif words[0] == 'run-as': result = DIGEST + '  files/app.preferences_pb'
                return (result, 0)
        snapshot = dict(awake=True, keyguardShowing=False, focusedWindow='Launcher', thermalStatus=0, batteryTemperatureC=32)
        with patch.object(perf, 'ROOT', root), patch.object(perf, 'Archive', FakeArchive), \
             patch.object(perf, 'archive_untracked_sources', return_value={}), \
             patch.object(perf, 'parse_conditions', return_value=snapshot):
            return perf.main(['--serial', 'fake', '--output', str(output), '--mode', 'app', '--trials', '1', '--runs', '1'])

    def test_full_trace_requires_diagnostic_mode_before_creating_archive(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / 'result'
            with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                perf.main(['--serial', 'fake', '--output', str(output), '--mode', 'app', '--full-trace'])
            self.assertEqual(2, error.exception.code)
            self.assertFalse(output.exists())

    def test_valid_run_is_archived(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.assertEqual(0, self.run_fake(root, root / 'result'))
            result = json.loads((root / 'result/run.json').read_text())
            self.assertTrue(result['measurementValid']); self.assertTrue(result['timingComparable'])

    def test_cleanup_failure_invalidates_successful_trial(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.assertEqual(1, self.run_fake(root, root / 'result', cleanup_failure=True))
            result = json.loads((root / 'result/run.json').read_text())
            self.assertFalse(result['measurementValid']); self.assertFalse(result['timingComparable'])
            self.assertTrue(result['trials'][0]['valid']); self.assertTrue(result['cleanupErrors'])

    def test_interrupted_trial_is_retained_and_invalid(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.assertEqual(1, self.run_fake(root, root / 'result', interrupted=True))
            result = json.loads((root / 'result/run.json').read_text())
            self.assertFalse(result['measurementValid']); self.assertTrue(result['screenTimeoutRestored'])
            self.assertIn('KeyboardInterrupt', result['failure'])
            self.assertFalse(json.loads((root / 'result/trial-01/trial.json').read_text())['valid'])


if __name__ == '__main__':
    unittest.main()
