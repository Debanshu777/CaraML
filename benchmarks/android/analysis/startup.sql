INCLUDE PERFETTO MODULE android.startup.startups;

CREATE PERFETTO TABLE launch AS
SELECT * FROM android_startups
WHERE package = 'com.debanshu777.caraml.benchmark'
ORDER BY ts DESC LIMIT 1;

CREATE PERFETTO TABLE app_slices AS
SELECT s.*, t.name AS thread_name, t.is_main_thread
FROM slice s
JOIN thread_track tt ON tt.id = s.track_id
JOIN thread t USING (utid)
JOIN process p USING (upid)
WHERE p.name = 'com.debanshu777.caraml.benchmark' AND s.dur > 0;

-- These are inclusive, overlapping intervals, NOT additive percentages of TTID.
WITH phases AS (
  SELECT CASE
    WHEN name GLOB 'DrawFrames *' THEN 'RenderThread:firstDraw'
    ELSE name END AS metric, app_slices.dur
  FROM app_slices, launch l
  WHERE app_slices.ts >= l.ts AND app_slices.ts < l.ts_end
    AND ((is_main_thread AND name IN (
      'bindApplication', 'activityStart', 'activityResume', 'Compose:initializeView',
      'Compose:recompose', 'AndroidOwner:onMeasure', 'AndroidOwner:onLayout'))
      OR (thread_name = 'RenderThread' AND name = 'CreateGraphicsPipeline-CompileAfterCacheMiss')
      OR id = (SELECT id FROM app_slices
        WHERE thread_name = 'RenderThread' AND name GLOB 'DrawFrames *'
        AND ts >= l.ts AND ts < l.ts_end ORDER BY ts LIMIT 1))
)
SELECT metric, COUNT(*) AS count, SUM(dur) / 1e6 AS total_ms, MAX(dur) / 1e6 AS max_ms
FROM phases GROUP BY metric
UNION ALL
SELECT 'startup:TTID', 1, dur / 1e6, dur / 1e6 FROM launch
UNION ALL
SELECT 'main:state:' || state, COUNT(*),
  SUM(MIN(s.ts + s.dur, l.ts_end) - MAX(s.ts, l.ts)) / 1e6,
  MAX(MIN(s.ts + s.dur, l.ts_end) - MAX(s.ts, l.ts)) / 1e6
FROM thread_state s
JOIN thread t USING (utid)
JOIN process p USING (upid), launch l
WHERE p.name = 'com.debanshu777.caraml.benchmark' AND t.is_main_thread
  AND s.dur > 0 AND s.ts < l.ts_end AND s.ts + s.dur > l.ts
GROUP BY state;
