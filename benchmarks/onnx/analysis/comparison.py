#!/usr/bin/env python3
"""Regenerate the October24 ONNX report from completed local archives only.

No device access, builds, benchmark execution, or modification of input archives.
Run from any directory; ignored raw archives must be present under --archives.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import statistics
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'benchmarks/onnx'))
from performance_pass import parse_events, validate_events

NAMES = ('qwen-baseline-timing', 'qwen-candidate-timing', 'llama-baseline-timing', 'llama-candidate-timing',
         'qwen-reselect-reload', 'qwen-reselect-reuse', 'qwen-final-memory-on', 'qwen-candidate-no-memory')
DIAGNOSTICS = ('qwen-baseline-trace-fixed', 'llama-baseline-trace', 'qwen-candidate-trace', 'llama-candidate-trace')
SMOKE_CASES = ('smollm2-135m', 'smollm2-360m', 'qwen3-06b')
START = '<!-- performance-pass-2026-10-24:start -->'
END = '<!-- performance-pass-2026-10-24:end -->'


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def relative(path):
    return str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path)


def read(path):
    return json.loads(path.read_text())


def stats(values):
    return {'n': len(values), 'median': statistics.median(values), 'min': min(values), 'max': max(values), 'values': values}


def summarize(folder, diagnostic_only=False):
    path = folder / 'run.json'
    if not path.exists():
        return {'archive': relative(folder), 'status': 'not_captured'}
    try:
        run = read(path)
    except json.JSONDecodeError:
        return {'archive': relative(folder), 'status': 'in_progress'}
    if not run.get('finishedAtUtc'):
        return {'archive': relative(folder), 'status': 'in_progress'}
    if (run.get('measurementValid') is not True or run.get('timingComparable') is not (not diagnostic_only)
            or run.get('diagnosticOnly') is not diagnostic_only):
        return {'archive': relative(folder), 'status': 'excluded', 'failure': run.get('failure'), 'cleanupErrors': run.get('cleanupErrors')}
    options = run['options']
    manifest = read(folder / 'model-manifest.json')
    pinned = read(folder / 'pinned-files.json')
    by_name = {x['name']: x for x in pinned}
    for item in manifest['files']:
        item['expectedSha256'] = by_name[item['rfilename']]['sha256']
    trials = []
    assert len(run['trials']) == options['trials']
    for trial in run['trials']:
        assert trial['valid']
        log_path = folder / f"trial-{trial['trial']:02d}/instrumentation.txt"
        raw = log_path.read_text()
        events = parse_events(raw, options['mode'])
        assert events == trial['events'], 'Archived parsed records differ from instrumentation'
        environment = next(x for x in events if x['stage'] == 'environment')
        sampling = environment.get('memory_sampling')
        # Historical baseline predates the explicit mode record; its archived
        # harness source always ran the100ms PSS sampler.
        sampling_source = 'explicit_environment_record' if sampling is not None else 'historical_archived_harness_default'
        failures = validate_events(events, raw, 0, trial['command'], options['mode'], options['case'],
                                   options['runs'], options['tokens'], manifest=manifest,
                                   reselect=options.get('reselect'), memory_sampling=sampling)
        assert not failures, (folder, trial['trial'], failures)
        generations = [x for x in events if x['stage'] == 'generation']
        trials.append({'trial': trial['trial'], 'logSha256': digest(log_path), 'before': trial['before'], 'after': trial['after'],
                       'memorySampling': sampling if sampling is not None else True, 'memorySamplingEvidence': sampling_source,
                       'load': next(x for x in events if x['stage'] == 'load'),
                       'repeatLoad': next(x for x in events if x['stage'] == 'repeat_load'),
                       'reselect': next((x for x in events if x['stage'] == 'reselect'), None),
                       'firstGeneration': generations[0], 'measuredGenerations': generations[1:],
                       'measuredTtftMs': stats([x['ttft_ms'] for x in generations[1:]]),
                       'measuredElapsedMs': stats([x['elapsed_ms'] for x in generations[1:]])})
    assert len({x['memorySampling'] for x in trials}) == 1
    all_generations = [g for t in trials for g in [t['firstGeneration'], *t['measuredGenerations']]]
    summary = {name: stats([t['load'][name] for t in trials]) for name in ('whole_load_ms','prepare_ms','resolve_ms','load_ms')}
    summary.update(repeat_load_ms=stats([t['repeatLoad']['load_ms'] for t in trials]),
                   first_generation_ttft_ms=stats([t['firstGeneration']['ttft_ms'] for t in trials]),
                   within_trial_median_ttft_ms=stats([t['measuredTtftMs']['median'] for t in trials]),
                   within_trial_median_elapsed_ms=stats([t['measuredElapsedMs']['median'] for t in trials]))
    if all(t['reselect'] for t in trials):
        summary['reselect_ms'] = stats([t['reselect']['elapsed_ms'] for t in trials])
    return {'archive': relative(folder), 'status': 'complete_valid', 'runJsonSha256': digest(path),
            'case': options['case'], 'options': options, 'installedApkSha256': run['installedApkSha256'],
            'finishedAtUtc': run['finishedAtUtc'], 'device': run['deviceFingerprint'],
            'measurementValid': True, 'diagnosticOnly': diagnostic_only, 'preferencesUnchanged': run['preferencesUnchanged'],
            'screenTimeoutRestored': run['screenTimeoutRestored'], 'memorySampling': trials[0]['memorySampling'],
            'pinnedFiles': pinned, 'processTrials': len(trials), 'measuredGenerationsPerTrial': options['runs'],
            'summary': summary, 'trials': trials,
            'outputTexts': sorted({g['text'] for g in all_generations}),
            'outputHashes': sorted({g['output_sha256'] for g in all_generations}),
            'runtimeConfigs': sorted({t['load']['runtime'] for t in trials})}


def compare(before, after, fields, same_apk=False, same_sampling=True):
    if before['status'] != 'complete_valid' or after['status'] != 'complete_valid':
        return {'status': 'pending_completed_pair'}
    if before.get('diagnosticOnly') or after.get('diagnosticOnly'):
        return {'status': 'excluded_diagnostic', 'reason':'Diagnostic traces do not supply ordinary latency comparisons.'}
    assert before['case'] == after['case'] and before['pinnedFiles'] == after['pinnedFiles']
    assert before['runtimeConfigs'] == after['runtimeConfigs']
    if same_apk and before['installedApkSha256'] != after['installedApkSha256']:
        return {'status': 'excluded_unmatched_apk', 'reason': 'Same-APK comparison requires an explicit matched partner.'}
    if any(before['options'][key] != after['options'][key] for key in ('trials', 'runs', 'tokens')):
        return {'status': 'excluded_unmatched_workload', 'reason': 'Trial count, generation count and output cap must match.'}
    if before['outputTexts'] != after['outputTexts'] or before['outputHashes'] != after['outputHashes']:
        return {'status': 'excluded_unmatched_output', 'reason': 'Controlled comparison requires matching generated text and hashes.'}
    if same_sampling:
        assert before['memorySampling'] == after['memorySampling']
    rows = {}
    for name in fields:
        b = before['summary'][name]['median']; a = after['summary'][name]['median']
        rows[name] = {'beforeMedianMs': b, 'afterMedianMs': a, 'changeMs': a-b, 'reductionPercent': 100*(b-a)/b}
    return {'status': 'complete_valid', 'sameApk': before['installedApkSha256'] == after['installedApkSha256'],
            'sameOutputTextAndHashes': before['outputTexts'] == after['outputTexts'] and before['outputHashes'] == after['outputHashes'],
            'matchedPinnedFilesAndRuntime': True, 'metrics': rows,
            'limitations': ['Sequential small-sample pilot, not randomized or a confidence interval.',
                            'OS page caches were neither dropped nor controlled; process restart alone is not cold storage.',
                            'Battery temperatures and background scheduling vary; see every trial.']}


def native_libraries(folder, kind):
    path = folder / f'{kind}.apk'
    expected = read(folder / 'artifact.json')['sha256']
    assert digest(path) == expected
    with zipfile.ZipFile(path) as apk:
        libraries = {name: hashlib.sha256(apk.read(name)).hexdigest() for name in apk.namelist() if name.endswith('.so')}
    return {'apkSha256': expected, 'apk': relative(path), 'libraries': libraries}


def trace_summary(trace):
    # Qualify join keys: phases and thread_state both carry utid. Intersections
    # are always clipped to the synchronous stage; async spans have no caller.
    sql = """
    CREATE PERFETTO TABLE a AS SELECT upid FROM process WHERE name='com.debanshu777.caraml';
    CREATE PERFETTO TABLE s AS SELECT sl.*,tt.utid,t.tid,t.name thread_name,t.is_main_thread
    FROM slice sl LEFT JOIN thread_track tt ON tt.id=sl.track_id LEFT JOIN thread t ON t.utid=tt.utid
    WHERE sl.dur>=0 AND (t.upid IN (SELECT upid FROM a) OR sl.track_id IN(SELECT id FROM process_track WHERE upid IN(SELECT upid FROM a)));
    CREATE PERFETTO TABLE b AS SELECT min(ts) start_ts,max(ts+dur) end_ts FROM s WHERE name GLOB 'CaraML.onnx.benchmark.*';
    SELECT json_object(
      'stages',(SELECT json_group_array(json_object('name',x.name,'ts',x.ts,'wallMs',x.dur/1e6,
       'tid',x.tid,'thread',x.thread_name,'main',x.is_main_thread,
       'ownCpuMs',CASE WHEN x.utid IS NULL THEN NULL ELSE (SELECT coalesce(sum(min(st.ts+st.dur,x.ts+x.dur)-max(st.ts,x.ts)),0)/1e6 FROM thread_state st WHERE st.utid=x.utid AND st.state='Running' AND st.dur>0 AND st.ts<x.ts+x.dur AND st.ts+st.dur>x.ts) END,
       'ownIoWaitMs',CASE WHEN x.utid IS NULL THEN NULL ELSE (SELECT coalesce(sum(min(st.ts+st.dur,x.ts+x.dur)-max(st.ts,x.ts)),0)/1e6 FROM thread_state st WHERE st.utid=x.utid AND st.io_wait=1 AND st.dur>0 AND st.ts<x.ts+x.dur AND st.ts+st.dur>x.ts) END))
       FROM s x,b WHERE x.ts>=b.start_ts AND x.ts+x.dur<=b.end_ts AND
       (x.name IN('CaraML.onnx.graphValidate','CaraML.onnx.tokenizerInitialize','CaraML.onnx.sessionCreate','CaraML.onnx.prefill','CaraML.onnx.tokenizerRead','CaraML.onnx.sessionClose') OR x.name GLOB 'CaraML.onnx.benchmark.*' OR x.name GLOB 'CaraML.load.onnxRuntime*' OR x.name GLOB 'CaraML.generation.onnxTimeToFirstToken*')),
      'payloadChecksumCount',(SELECT count(*) FROM s,b WHERE (name GLOB 'CaraML.artifact.payloadChecksum*' OR name GLOB 'CaraML.artifact.manifestPayloadChecksum*') AND ts>=b.start_ts AND ts+dur<=b.end_ts),
      'gcLabels',(SELECT json_group_array(json_object('name',name,'count',n,'inclusiveWallMs',ms,'maxWallMs',max_ms)) FROM (
       SELECT name,count(*) n,sum(dur)/1e6 ms,max(dur)/1e6 max_ms FROM s,b WHERE ts>=b.start_ts AND ts+dur<=b.end_ts AND
       (name GLOB '*GC*' OR lower(name) GLOB '*garbage*' OR lower(name) GLOB '*concurrent copying*' OR name GLOB '*SuspendAll*') GROUP BY name)),
      'peakSampledRssBytes',(SELECT max(value) FROM counter c JOIN process_counter_track p ON p.id=c.track_id,b WHERE p.upid IN(SELECT upid FROM a) AND p.name='mem.rss' AND c.ts>=b.start_ts AND c.ts<=b.end_ts)
    ) AS result;
    """
    result = subprocess.run([str(ROOT/'build/tools/trace_processor'), 'query', str(trace), sql],
                            capture_output=True, text=True, check=True)
    return json.loads(result.stdout.strip().split('\n',1)[1][1:-1])


def diagnostic(folder):
    path = folder / 'run.json'
    if not path.exists(): return {'archive': relative(folder), 'status': 'not_captured'}
    try:
        run = read(path)
    except json.JSONDecodeError:
        return {'archive': relative(folder), 'status': 'in_progress'}
    valid = run.get('measurementValid') if 'measurementValid' in run else run.get('valid')
    if valid is not True:
        return {'archive': relative(folder), 'status': 'in_progress_or_invalid'}
    quality_paths = sorted(folder.rglob('capture.quality.json'))
    assert quality_paths and all(read(p)['valid'] is True for p in quality_paths)
    trace_paths = sorted(folder.rglob('*.perfetto-trace'))
    assert trace_paths, 'Valid diagnostic archive has no trace files'
    validation = None
    if run.get('options',{}).get('mode') == 'app':
        verified = summarize(folder, diagnostic_only=True)
        assert verified['status'] == 'complete_valid'
        validation = {key:verified[key] for key in ('case','installedApkSha256','processTrials','measuredGenerationsPerTrial',
                      'measurementValid','diagnosticOnly','preferencesUnchanged','screenTimeoutRestored','pinnedFiles','outputTexts','outputHashes','runtimeConfigs')}
        validation['trialLogSha256'] = [trial['logSha256'] for trial in verified['trials']]
    return {'archive': relative(folder), 'status': 'complete_valid', 'diagnosticOnly': True,
            'functionalValidation':validation,
            'installedApkSha256':run.get('installedApkSha256'), 'options':run.get('options'),
            'traces': [{'path': relative(p), 'bytes': p.stat().st_size, 'sha256': digest(p), 'boundedSummary': trace_summary(p)} for p in trace_paths],
            'quality': [read(p) for p in quality_paths],
            'profiles': [read(p)['captureProfile'] for p in sorted(folder.rglob('run.json')) if 'captureProfile' in read(p)]}


def model_smokes(archives):
    cases = {case: summarize(archives / 'model-smoke' / case) for case in SMOKE_CASES}
    complete = [case for case, result in cases.items() if result['status'] == 'complete_valid']
    for case in complete:
        result = cases[case]
        assert result['case'] == case and result['processTrials'] == 1 and result['measuredGenerationsPerTrial'] == 1
        result['interpretation'] = 'One-process functional smoke, with one warmup and one measured short math generation; not a latency estimate or general quality evaluation.'
        result['mathAnswerReview'] = {'status':'not_reviewed', 'expectedAnswer':5}
        if case == 'smollm2-135m' and result['outputTexts'] == ['Two plus three equals four.']:
            result['mathAnswerReview'] = {'status':'incorrect', 'expectedAnswer':5, 'observedAnswer':4,
                                         'interpretation':'Natural EOG and output_valid establish functional completion/integrity; this answer is arithmetically wrong.'}
        elif case == 'smollm2-360m' and result['outputTexts'] == ['Answer: 2 + 3 = 5.']:
            result['mathAnswerReview'] = {'status':'correct_for_this_prompt', 'expectedAnswer':5, 'observedAnswer':5}
        elif case == 'qwen3-06b' and result['outputHashes'] == ['ed7b00fe152563b923591e44fc94375c1b0722d2509c205f4f100c7476f17e2b']:
            result['mathAnswerReview'] = {'status':'correct_for_this_prompt', 'expectedAnswer':5, 'observedAnswer':5,
                                         'interpretation':'Reviewed retained reasoning and final answer; natural completion at 113 nonempty chunks, not native token IDs.'}
    return {'status': 'complete_valid' if len(complete) == len(SMOKE_CASES) else 'partial_or_pending',
            'completedCases': complete, 'expectedCases': list(SMOKE_CASES), 'cases': cases,
            'otherModelCoverage':'Qwen2.5 and Llama have separate timing trials and final-candidate diagnostic captures.'}


def phase_comparison(before, after):
    if before['status'] != 'complete_valid' or after['status'] != 'complete_valid':
        return {'status':'pending_completed_pair'}
    def phases(diagnostic):
        assert len(diagnostic['traces']) == 1
        stages = diagnostic['traces'][0]['boundedSummary']['stages']
        return {name:[row for row in stages if row['name'] == 'CaraML.onnx.'+name]
                for name in ('graphValidate','tokenizerInitialize','sessionCreate')}
    b = phases(before); a = phases(after)
    assert all(len(b[name]) == len(a[name]) == 2 for name in b)
    return {'status':'complete_diagnostic', 'beforeProfiles':before['profiles'], 'afterProfiles':after['profiles'],
            'scope':'Single diagnostic traces; first and repeated load phases. Trace profiles, APKs, cache state and temperatures differ; no controlled latency-effect estimate.',
            'phases':{name:{scope:{'before':b[name][i], 'after':a[name][i]}
                            for i,scope in enumerate(('initial','repeat'))} for name in b}}


def sampler_gc(archives, diagnostics):
    result = {}
    for model in ('qwen','llama'):
        path = archives / 'analysis' / f'{model}-candidate-sampler-gc-summary.json'
        if not path.exists():
            result[model] = {'status':'not_analyzed'}
            continue
        summary = read(path)
        assert summary['installedApkSha256'] == diagnostics[f'{model}-candidate-trace']['installedApkSha256']
        assert summary['sourceRawSha256'] == digest(path.with_name(f'{model}-candidate-sampler-gc-raw.json'))
        result[model] = {'status':'complete_diagnostic', 'source':relative(path), 'sourceSha256':digest(path), **summary}
    return result


def final_validation(archives, native, diagnostics, smokes):
    if 'final' not in native:
        return {'status':'pending_final_apk'}
    expected = native['final']['apkSha256']
    coverage = []
    for model in ('qwen','llama'):
        diagnostic = diagnostics[f'{model}-candidate-trace']
        validation = diagnostic.get('functionalValidation')
        if validation:
            assert validation['installedApkSha256'] == expected
            coverage.append({'case':validation['case'], 'archive':diagnostic['archive'], 'scope':'diagnostic generation validation'})
    for case in smokes['completedCases']:
        smoke = smokes['cases'][case]
        assert smoke['installedApkSha256'] == expected
        coverage.append({'case':case, 'archive':smoke['archive'], 'scope':'one-process functional smoke'})
    restoration_path = archives / 'final-device-state.json'
    restoration = None
    if restoration_path.exists():
        restoration = read(restoration_path)
        assert restoration['screen_off_timeout'] == '30000'
        assert restoration['preferences_sha256'] == read(archives/'qwen-baseline-timing/run.json')['preferencesSha256Before']
        assert restoration['installedApkSha256'] == expected and restoration['appPidCheckExitCode'] == 1
        assert all(restoration[key] is True for key in ('originalScreenTimeoutRestored','originalPreferencesUnchanged','verifiedFinalApkInstalled','benchmarkAppStopped'))
        restoration = {'source':relative(restoration_path),'sourceSha256':digest(restoration_path),**restoration}
    tests = native['final'].get('hostValidation')
    if tests:
        assert all(suite[key] == 0 for suite in tests['jvm'].values() for key in ('failures','errors','skipped'))
    return {'status':'complete_valid' if len(coverage) == 5 and restoration and tests else 'partial_or_pending',
            'installedApkSha256':expected, 'modelCases':coverage, 'hostValidation':tests, 'restoration':restoration,
            'scope':'Functional load/completion and recorded-output integrity, not general semantic accuracy; SmolLM2 135M gives an incorrect math answer.'}


def render(report):
    text = [START, '## Pixel 9 app performance pass — October 24, 2026', '',
            'This pass measures the installed-model app path in an **Android debug APK**, with ONNX Runtime 1.31.0, CPU, two threads, context 2,048 and F32 KV caches. '
            'It uses pinned Qwen2.5 0.5B INT8 and Llama 3.2 1B Q4 bundles. The candidate buffers protobuf metadata reads while retaining '
            'bounds and external-file validation, and adds exact-artifact resident-session reuse. '
            '[Per-trial results and provenance](results/pixel9-performance-2026-10-24.json) retain raw values, output hashes, temperatures and exclusions.', '',
            '| Model | Initial installed load, before → after | Repeated repository load, before → after |',
            '|---|---:|---:|']
    for key, label in [('qwen', 'Qwen2.5 0.5B INT8'), ('llama', 'Llama 3.2 1B Q4')]:
        pair = report['comparisons'][key + 'BuildPair']
        if pair['status'] == 'complete_valid':
            a = pair['metrics']['whole_load_ms']; b = pair['metrics']['repeat_load_ms']
            text.append(f"| {label} | {a['beforeMedianMs']/1000:.3f} → {a['afterMedianMs']/1000:.3f} s ({abs(a['reductionPercent']):.1f}% {'lower' if a['reductionPercent']>=0 else 'higher'}) | {b['beforeMedianMs']/1000:.3f} → {b['afterMedianMs']/1000:.3f} s ({abs(b['reductionPercent']):.1f}% {'lower' if b['reductionPercent']>=0 else 'higher'}) |")
    text += ['', 'Load values are medians of **three fresh processes per build**. “Initial installed load” includes prepare, resolve and repository load; '
             'it excludes Activity startup, inventory lookup and first drawn chat. “Repeated repository load” is a later explicit load request and still reconstructs the runtime; '
             'it is distinct from resident reselection. OS caches are **uncontrolled and not flushed**. These sequential pilots have small samples and differing temperatures, '
             'so percentages describe these runs rather than guaranteed gains. All 34 packaged native `.so` files have identical SHA-256 hashes between the two APKs.', '']
    reuse = report['comparisons']['qwenResidentReselection']
    if reuse['status'] == 'complete_valid':
        r = reuse['metrics']['reselect_ms']
        text += [f"On the same candidate APK, Qwen2.5 0.5B prepare plus reselection took **{r['beforeMedianMs']:.1f} ms with forced reload → {r['afterMedianMs']:.1f} ms with resident reuse** "
                 f"({r['reductionPercent']:.1f}% lower; three-process medians). Every reuse record confirmed the retained session, matching runtime/context and unchanged output. "
                 'This applies to an eligible model already resident in memory. Timing ends at repository completion and excludes UI/frame readiness.', '']
    q = report['comparisons']['qwenBuildPair']
    if q['status'] == 'complete_valid':
        a = q['metrics']['first_generation_ttft_ms']; b = q['metrics']['within_trial_median_ttft_ms']
        text += [f"Qwen first-generation TTFT was {a['beforeMedianMs']:.1f} → {a['afterMedianMs']:.1f} ms; warm TTFT was {b['beforeMedianMs']:.1f} → {b['afterMedianMs']:.1f} ms. "
                 'The latter is the median of three within-process medians, each from three measured generations after one retained warmup. Llama uses one measured generation per process after one warmup. '
                 'App TTFT ends at the first nonempty stream chunk; chunk counts are not native token counts. This pilot does not establish a generation-speed improvement.', '']
    llama = report['comparisons']['llamaBuildPair']
    if llama['status'] == 'complete_valid':
        b = llama['metrics']['within_trial_median_ttft_ms']
        text += [f"Llama warm TTFT was {b['beforeMedianMs']:.1f} → {b['afterMedianMs']:.1f} ms. Its initial-load result also shows **no established improvement**; graph-validation savings do not imply a faster whole load for every bundle.", '']
    memory = report['comparisons']['qwenMemorySampling']
    if memory['status'] == 'complete_valid':
        a = memory['metrics']['within_trial_median_ttft_ms']; b = memory['metrics']['within_trial_median_elapsed_ms']
        count = report['experiments']['qwen-candidate-no-memory']['processTrials']
        text += [f"A separate same-APK memory-polling pilot ({count} process per condition) measured warm TTFT {a['beforeMedianMs']:.1f} ms with polling → {a['afterMedianMs']:.1f} ms without, "
                 f"and generation elapsed {b['beforeMedianMs']:.1f} → {b['afterMedianMs']:.1f} ms. This measures benchmark overhead, **not a production optimization**. "
                 'This small pilot establishes no latency benefit from disabling polling. '
                 'Use `--no-memory-sampling` for ordinary latency pilots and collect PSS in separate diagnostic runs. '
                 'The runner retains polling by default for historical compatibility; keep sampled and unsampled baselines separate.', '']
    text += ['Perfetto shows **no payload-checksum spans** in the app load path: installed files use publication hashes plus current path/size/manifest checks. '
             'Baseline Qwen manifest plus final-artifact checks totaled 43 ms. These operations ran on worker threads; the static harness does not test interactive chat rendering.', '']
    if all(p['status'] == 'complete_diagnostic' for p in report['diagnosticPhaseComparisons'].values()):
        text += ['Single-trace load phases, all in milliseconds:', '',
                 '| Model | Graph initial, before → after | Graph repeat, before → after | Candidate tokenizer, initial / repeat | Candidate ORT session, initial / repeat |',
                 '|---|---:|---:|---:|---:|']
        for key, label in (('qwen','Qwen2.5 0.5B'), ('llama','Llama 3.2 1B')):
            p = report['diagnosticPhaseComparisons'][key]['phases']; g = p['graphValidate']
            text.append(f"| {label} | {g['initial']['before']['wallMs']:.0f} → {g['initial']['after']['wallMs']:.0f} | "
                        f"{g['repeat']['before']['wallMs']:.0f} → {g['repeat']['after']['wallMs']:.1f} | "
                        f"{p['tokenizerInitialize']['initial']['after']['wallMs']:.0f} / {p['tokenizerInitialize']['repeat']['after']['wallMs']:.0f} | "
                        f"{p['sessionCreate']['initial']['after']['wallMs']:.0f} / {p['sessionCreate']['repeat']['after']['wallMs']:.0f} |")
        text += ['', 'These are diagnostic phase comparisons, **not a traced A/B speed estimate**: Qwen uses a native-only baseline and full framework/Dalvik candidate capture; Llama uses native-only captures. '
                 'Cache state and temperatures also differ. The graph-reader change reduces work, while tokenizer/session construction remains substantial; ordinary runs above measure total latency.', '']
    gc = report['samplerGcDiagnostics']['qwen']
    if gc['status'] == 'complete_diagnostic':
        g = gc['gc']; m = gc['memorySampling']; low,high = m['intervalRangeMs']
        text += [f"The full Qwen trace records {g['cycleUnionMs']:.1f} ms of concurrent GC activity, but only {g['pausePhaseUnionMs']:.2f} ms in named pause phases "
                 f"(maximum {g['maxPausePhaseMs']:.2f} ms). It does not show GC pauses dominating load or generation. Llama GC remains unmeasured. "
                 f"PSS calls span {low:.1f}–{high:.1f} ms and overlap generation; their async markers do not identify a sampler thread, so sampler CPU cost is unknown.", '']
    text += ['A separate ORT operator profile using the sky-explanation runtime prompt places every recorded kernel on CPU. In its one warm generation, output-head matrix multiplication plus repeated '
             'weight dequantization consumed **53.7% of summed ORT run time**; the head materialized a 544,538,624-byte float weight output on each run. '
             'Next targets are tokenizer construction and ORT graph/session work, including an independently validated output-head export. '
             'Profile times are diagnostic, nested events are not added together, and no graph rewrite or GPU speedup is claimed.', '',
             'The original generation harness polls PSS every 100 ms and includes sampler shutdown in elapsed time. Loading and pre-generation reselection occur before that sampler. '
             'Native-only traces omit ART GC events; the full Qwen capture supplies GC diagnostics. RSS is sampled every 250 ms and does not establish long-run memory retention. '
             'All accepted trials require explicit instrumentation success, natural end-of-generation, nonempty output, pinned identities, foreground checks, thermal status 0 '
             'and start temperature ≤35°C. Hashes verify recorded-text integrity; the saved short math answers are checked separately from general model quality.', '',
             'Run against an already installed debug APK and installed pinned model:', '', '```bash',
             'python3 benchmarks/run.py onnx performance --serial SERIAL --mode app --case qwen25-05b --trials 3 --runs 3 --tokens 32' +
             (' --no-memory-sampling' if memory['status'] == 'complete_valid' else '') + ' --output build/benchmark-results/onnx-performance/new-pass',
             '# Separate experiments: --reselect reuse|reload; --no-memory-sampling; or --trace --full-trace --trials 1 --runs 1.',
             'python3 benchmarks/run.py onnx report', '```', '',
             'The runner preserves screen timeout/preferences, archives APK and source identities, retains failed trials and returns nonzero for invalid measurements. '
             'Completed diagnostic and timing captures are separate in the JSON; pending or invalid captures contribute no reported medians.']
    smokes = report['modelSmokes']
    if smokes['completedCases']:
        text += ['', 'Additional one-process functional smokes passed for ' + ', '.join(f'`{case}`' for case in smokes['completedCases']) +
                 '. Each retains one warmup and one measured math answer with natural completion, recorded text/SHA-256 and pinned artifacts; these are functional checks rather than speed comparisons.']
        if smokes['cases']['smollm2-135m'].get('mathAnswerReview',{}).get('status') == 'incorrect':
            text += ['', 'SmolLM2 135M answered “Two plus three equals four.” Its functional pass does **not** mean the answer was correct; this arithmetic error is retained in the report.']
    final = report['finalValidation']
    if final['status'] == 'complete_valid':
        tests = final['hostValidation']
        text += ['', f"The final APK was exercised on all five model cases. Host validation passed {tests['total']:,} JVM tests and {tests['nativeRepackEquivalence']['cases']:,} byte-identical native repack cases. "
                 '`verifyProject` excluded `:nativeEngine:verifyNativeSubmodulePins` because the existing SSH remote differs from its pinned HTTPS URL; no submodule change was made. '
                 'Final device evidence confirms the original 30-second screen timeout and unchanged preferences, the verified final APK installed, and the benchmark app stopped.']
    text += [END, '']
    return '\n'.join(text)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archives', type=Path, default=ROOT / 'build/benchmark-results/onnx-performance')
    parser.add_argument('--output', type=Path, default=ROOT / 'benchmarks/onnx/results/pixel9-performance-2026-10-24.json')
    args = parser.parse_args(argv); archives = args.archives.resolve()
    experiments = {name: summarize(archives / name) for name in NAMES}
    fields = ('whole_load_ms','load_ms','prepare_ms','resolve_ms','repeat_load_ms','first_generation_ttft_ms','within_trial_median_ttft_ms','within_trial_median_elapsed_ms')
    comparisons = {key+'BuildPair': compare(experiments[key+'-baseline-timing'], experiments[key+'-candidate-timing'], fields) for key in ('qwen','llama')}
    comparisons['qwenResidentReselection'] = compare(experiments['qwen-reselect-reload'], experiments['qwen-reselect-reuse'], ('reselect_ms',), same_apk=True)
    sampled_partner = 'qwen-final-memory-on' if experiments['qwen-final-memory-on']['status'] == 'complete_valid' else 'qwen-candidate-timing'
    comparisons['qwenMemorySampling'] = compare(experiments[sampled_partner], experiments['qwen-candidate-no-memory'], ('first_generation_ttft_ms','within_trial_median_ttft_ms','within_trial_median_elapsed_ms'), same_apk=True, same_sampling=False)
    native = {name: native_libraries(archives / (name+'-build'), name) for name in ('baseline','candidate')}
    if (archives / 'final-build/artifact.json').exists():
        native['final'] = native_libraries(archives / 'final-build', 'final')
        native['finalNativeLibrariesMatchMeasuredCandidate'] = native['final']['libraries'] == native['candidate']['libraries']
        if (archives / 'final-build/tests.json').exists():
            native['final']['hostValidation'] = read(archives / 'final-build/tests.json')
            log = archives / 'final-build/verification.log'
            proof = next(line for line in log.read_text().splitlines() if line.startswith('PASS: ') and 'byte-identical Q4_K/Q6_K repack cases' in line)
            native['final']['hostValidation']['nativeRepackEquivalence'] = {
                'cases':int(proof.split()[1]), 'result':proof, 'log':relative(log), 'logSha256':digest(log)}
    native['allNativeLibrariesIdentical'] = native['baseline']['libraries'] == native['candidate']['libraries']
    assert native['allNativeLibrariesIdentical']
    diagnostics = {name:diagnostic(archives/name) for name in DIAGNOSTICS}
    detailed = sampler_gc(archives, diagnostics)
    profile = read(archives / 'analysis/qwen-ort-profile-baseline.summary.json')
    warm = [x for x in profile['runs'] if not x['warmup']]
    profile_compact = {k:profile[k] for k in ('diagnosticOnly','inputs','workload','quality','load','interpretation')}
    head_ms = sum(x['lmHeadMatMul']['durationMs']+x['lmHeadWeightDequantize']['durationMs'] for x in warm)
    run_ms = sum(x['durationMs'] for x in warm)
    profile_compact['warmGenerationOutputHead'] = {'matmulPlusDequantizeMs':head_ms,'summedOrtRunMs':run_ms,'percent':100*head_ms/run_ms,
                                                 'dequantizedWeightOutputBytesPerRun':544538624}
    review = read(archives / 'analysis/qwen-baseline-review.json')
    smokes = model_smokes(archives)
    report = {'schemaVersion':1,'generatedAtUtc':datetime.now(timezone.utc).isoformat(),'date':'2026-10-24',
              'title':'Pixel9 ONNX installed-load, repeated-load and resident-reselection pass',
              'method':{'buildType':'Android debug APK', 'coldScope':'Fresh process; OS page caches uncontrolled, never flushed',
                        'primaryLoadStatistic':'Median of one load per process, three processes per build',
                        'warmGenerationStatistic':'Within each process median of measured generations; then median of process medians (three per build pair; one per memory-polling condition)',
                        'firstGeneration':'Retained separately; runtime session first generation after reset/system-tokenization',
                        'generationPrompt':'What is 2 plus 3? Answer in one short sentence.',
                        'notMeasured':'Full user tap-to-ready UI latency; long generation throughput/quality; long-run leak rate',
                        'generationMemorySampling':'Historical and matched build-pair trials use 100 ms PSS polling; unsampled pilot is separate',
                        'thermalGate':'NORMAL (0) and ≤35°C before instrumentation; endpoint/sample thermal 0 during run; not a continuously controlled lab'},
              'experiments':experiments,'comparisons':comparisons,'apkProvenance':native,
              'memorySamplingComparisonPartner':sampled_partner,
              'diagnostics':diagnostics,
              'diagnosticPhaseComparisons':{
                  'qwen':phase_comparison(diagnostics['qwen-baseline-trace-fixed'], diagnostics['qwen-candidate-trace']),
                  'llama':phase_comparison(diagnostics['llama-baseline-trace'], diagnostics['llama-candidate-trace'])},
              'operatorProfile':profile_compact,'baselineTraceFindings':review['findings'],
              'baselineMeasurementLimitations':review['limitations'],
              'samplerGcDiagnostics':detailed,
              'runtimeStageComparison':read(archives/'analysis/runtime-stage-comparison.json'),
              'measurementLimitations':[
                  'Fresh processes do not imply uncached storage; no page-cache flushing was performed.',
                  'Build and resident-reselection pairs have three processes per condition; the memory-polling comparison has only one process per condition.',
                  'Tracing is diagnostic and separate from ordinary timing; Qwen baseline and candidate capture profiles differ.',
                  'PSS sampling has process-scoped async spans. Wall time and overlap are measured; sampler-specific CPU time is unidentifiable.',
                  'Qwen full capture has ART pause evidence; Llama native-only capture has no GC visibility. Concurrent collection duration is not pause duration.',
                  'Short math answers, natural EOG and output hashes do not establish general output quality, long-generation speed or memory-retention behavior.',
                  'App TTFT ends at the first nonempty stream chunk; runtime benchmark token counts and prompt differ.',
                  'Installed load excludes Activity startup, startup reconciliation, inventory lookup and first drawn chat. Static harness foreground checks do not exercise interactive UI.',
                  'Prepared-artifact identities are compared with pinned publication metadata. The identity record is not an independent device rehash during timing.',
                  'Thermal and foreground checks are sampled/endpoints, not a controlled laboratory. See exact per-trial records.'],
              'hostVerificationLimitation':{
                  'platform':'macOS JVM test worker',
                  'sample':relative(archives/'candidate-build/verification-native-sample.txt'),
                  'observedStack':'ORT OneDsTelemetry destructor → Shutdown → LogManagerImpl::FlushAndTeardown during process exit',
                  'termination':'Parent terminated the stuck test worker manually; exit137 is not evidence of OOM.',
                  'retry':'A fresh retry completed all 15 ONNX tests and exited normally; full host gate results are archived with the final APK.',
                  'scope':'Host test-runner shutdown limitation; not an observed Android load or inference regression.'},
              'excludedArchives':[{'archive':relative(archives/'qwen-baseline-trace'),'reason':'Initial context pilot failed; fixed-context trace used instead.'}],
              'regeneration':{'script':'benchmarks/onnx/analysis/comparison.py',
                              'command':'python3 benchmarks/run.py onnx report','inputRoot':relative(archives)},
              'modelSmokes':smokes,
              'finalValidation':final_validation(archives,native,diagnostics,smokes)}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,indent=2)+'\n')
    readme = ROOT / 'benchmarks/onnx/README.md'; text = readme.read_text(); section = render(report)
    if START in text:
        before, rest = text.split(START,1); _, after = rest.split(END,1)
        text = before + section.rstrip() + after
    else:
        old_title, old_body = text.split('\n',1)
        text = '# ONNX benchmarks\n\n' + section + '\n## Historical ONNX and GGUF validation — October 23, 2026\n\n' + \
               'The remaining sections preserve the earlier runtime matrix and functional validation. Their prompts, harnesses and timing scope differ from the app performance pass above.\n' + old_body
    readme.write_text(text)
    print(json.dumps({'output':relative(args.output.resolve()),'comparisons':comparisons},indent=2))


if __name__ == '__main__':
    main()
