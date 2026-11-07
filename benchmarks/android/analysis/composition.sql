-- Use only with --full-tracing captures. Inclusive values overlap.
INCLUDE PERFETTO MODULE android.startup.startups;

WITH launch AS (
  SELECT * FROM android_startups
  WHERE package = 'com.debanshu777.caraml.benchmark'
  ORDER BY ts DESC LIMIT 1
), children AS (
  SELECT parent_id, SUM(dur) AS child_dur FROM slice WHERE dur > 0 GROUP BY parent_id
)
SELECT s.name, COUNT(*) AS calls,
  SUM(s.dur) / 1e6 AS inclusive_ms,
  SUM(MAX(0, s.dur - COALESCE(c.child_dur, 0))) / 1e6 AS self_ms
FROM slice s
JOIN thread_track tt ON tt.id = s.track_id
JOIN thread t USING (utid)
JOIN process p USING (upid)
LEFT JOIN children c ON c.parent_id = s.id, launch l
WHERE p.name = 'com.debanshu777.caraml.benchmark'
  AND t.is_main_thread AND s.dur > 0
  AND s.ts >= l.ts AND s.ts + s.dur <= l.ts_end
  AND s.name GLOB 'com.debanshu777.caraml*'
GROUP BY s.name ORDER BY self_ms DESC LIMIT 30;
