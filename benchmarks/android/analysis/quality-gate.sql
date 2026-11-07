-- Quality gates are per-file. Frame budgets are restricted to measureBlock when present.
CREATE PERFETTO TABLE measurement_window AS
SELECT ts, ts + dur AS end_ts FROM slice WHERE name = 'measureBlock' AND dur > 0
ORDER BY dur DESC LIMIT 1;
CREATE PERFETTO TABLE measured_frame_budgets AS
SELECT e.dur / 1e6 AS budget_ms
FROM expected_frame_timeline_slice e JOIN process p USING (upid)
WHERE p.name = 'com.debanshu777.caraml.benchmark' AND e.dur > 0
  AND e.surface_frame_token > 0
  AND (NOT EXISTS (SELECT 1 FROM measurement_window)
       OR EXISTS (SELECT 1 FROM measurement_window w WHERE e.ts >= w.ts AND e.ts + e.dur <= w.end_ts));
CREATE PERFETTO TABLE measured_frame_starts AS
SELECT DISTINCT e.ts FROM expected_frame_timeline_slice e JOIN process p USING (upid), measurement_window w
WHERE p.name = 'com.debanshu777.caraml.benchmark' AND e.surface_frame_token > 0 AND e.ts >= w.ts AND e.ts <= w.end_ts;
CREATE PERFETTO TABLE surface_cadence AS
SELECT (ts - LAG(ts) OVER (ORDER BY ts)) / 1e6 AS interval_ms FROM measured_frame_starts;
CREATE PERFETTO TABLE callback_cadence AS
SELECT (s.ts - LAG(s.ts) OVER (ORDER BY s.ts)) / 1e6 AS interval_ms
FROM slice s JOIN thread_track tt ON tt.id = s.track_id JOIN thread t USING (utid)
JOIN process p USING (upid), measurement_window w
WHERE p.name = 'com.debanshu777.caraml.benchmark' AND t.is_main_thread
AND s.name GLOB 'Choreographer#doFrame *' AND s.ts >= w.ts AND s.ts <= w.end_ts;
CREATE PERFETTO TABLE stream_updates AS
SELECT c.ts,c.value FROM counter c JOIN counter_track ct ON ct.id = c.track_id,measurement_window w
WHERE ct.name = 'CaraML.fixtureStreamUpdates' AND c.ts >= w.ts AND c.ts <= w.end_ts;
SELECT json_object(
  'durationMs', (SELECT (end_ts - start_ts) / 1e6 FROM trace_bounds),
  'errors', (SELECT json_group_array(json_object('name', name, 'index', idx, 'value', value, 'severity', severity))
             FROM stats WHERE value > 0 AND severity IN ('error', 'data_loss')),
  'cpuSamples', (SELECT count(*) FROM perf_sample),
  'gpuSlices', (SELECT count(*) FROM gpu_slice),
  'measurementWindowMs', (SELECT (end_ts - ts) / 1e6 FROM measurement_window),
  'frameBudgetMsMedian', (SELECT percentile(budget_ms, 50) FROM measured_frame_budgets),
  'frameBudgetMsP10', (SELECT percentile(budget_ms, 10) FROM measured_frame_budgets),
  'frameBudgetMsP90', (SELECT percentile(budget_ms, 90) FROM measured_frame_budgets),
  'frameCount', (SELECT count(*) FROM measured_frame_budgets),
  'surfaceFrameIntervalMsMedian', (SELECT percentile(interval_ms,50) FROM surface_cadence WHERE interval_ms>0),
  'callbackIntervalMsP10', (SELECT percentile(interval_ms,10) FROM callback_cadence WHERE interval_ms>0),
  'callbackIntervalMsMedian', (SELECT percentile(interval_ms,50) FROM callback_cadence WHERE interval_ms>0),
  'callbackIntervalMsP90', (SELECT percentile(interval_ms,90) FROM callback_cadence WHERE interval_ms>0),
  'vsyncPeriodObservationsNs', (SELECT json_group_array(DISTINCT c.value) FROM counter c JOIN counter_track ct ON ct.id=c.track_id, measurement_window w
      WHERE ct.name GLOB 'VsyncPeriod for *' AND c.value>0 AND c.ts>=w.ts AND c.ts<=w.end_ts),
  'streamUpdateCount', (SELECT count(*) FROM stream_updates WHERE value > 0),
  'streamUpdateSpanMs', (SELECT (MAX(ts)-MIN(ts))/1e6 FROM stream_updates)
) AS result;
