-- Thread slice durations are inclusive and may overlap; GPU wait is not GPU execution time.
SELECT t.name || ':' || s.name AS metric, COUNT(*) AS count,
  SUM(s.dur) / 1e6 AS total_ms, MAX(s.dur) / 1e6 AS max_ms
FROM slice s
JOIN thread_track tt ON tt.id = s.track_id
JOIN thread t USING (utid)
JOIN process p USING (upid)
WHERE p.name = 'com.debanshu777.caraml.benchmark' AND s.dur > 0
  AND s.name IN ('Compose:recompose', 'AndroidOwner:measureAndLayout',
    'HazeSource.record', 'flush layers', 'CreateGraphicsPipeline-CompileAfterCacheMiss')
GROUP BY t.name, s.name
UNION ALL
SELECT 'frames:' || a.jank_type || ':' || a.present_type, COUNT(*),
  SUM(a.dur) / 1e6, MAX(a.dur) / 1e6
FROM actual_frame_timeline_slice a JOIN process p USING (upid)
WHERE p.name = 'com.debanshu777.caraml.benchmark'
GROUP BY a.jank_type, a.present_type
UNION ALL
SELECT 'expected_frame_budget', COUNT(*), SUM(e.dur) / 1e6, MAX(e.dur) / 1e6
FROM expected_frame_timeline_slice e JOIN process p USING (upid)
WHERE p.name = 'com.debanshu777.caraml.benchmark';
