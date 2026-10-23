-- Run against model_load.py --trace captures, not UI Macrobenchmark traces.
SELECT s.name AS phase, t.name AS thread_name, s.dur / 1e6 AS wall_ms
FROM slice s
JOIN thread_track tt ON tt.id = s.track_id
JOIN thread t USING (utid)
JOIN process p USING (upid)
WHERE p.name = 'com.debanshu777.caraml'
  AND s.name IN ('CaraML.engineInitialize', 'CaraML.nativeModelLoad') AND s.dur > 0
ORDER BY s.ts;
