CREATE PERFETTO TABLE window AS SELECT ts,ts+dur ts_end FROM slice WHERE name='measureBlock' AND dur>0 ORDER BY dur DESC LIMIT 1;
CREATE PERFETTO TABLE app AS
 SELECT s.*,t.name thread_name,t.is_main_thread,t.utid FROM slice s JOIN thread_track tt ON tt.id=s.track_id
 JOIN thread t USING(utid) JOIN process p USING(upid),window w
 WHERE p.name='com.debanshu777.caraml.benchmark' AND s.dur>0 AND s.ts>=w.ts AND s.ts+s.dur<=w.ts_end;
CREATE PERFETTO TABLE frames AS
 SELECT a.id,a.ts,a.dur,a.surface_frame_token token,a.jank_type,a.present_type,e.dur budget,
 (a.ts+a.dur)-(e.ts+e.dur) overrun,
 (SELECT MAX(dur)/1e6 FROM app WHERE name='Choreographer#doFrame '||a.surface_frame_token) main_ms,
 (SELECT MAX(dur)/1e6 FROM app WHERE name='DrawFrames '||a.surface_frame_token) render_ms
 FROM actual_frame_timeline_slice a JOIN process p USING(upid)
 LEFT JOIN expected_frame_timeline_slice e ON e.upid=a.upid AND e.surface_frame_token=a.surface_frame_token,window w
 WHERE p.name='com.debanshu777.caraml.benchmark' AND a.ts>=w.ts AND a.ts+a.dur<=w.ts_end AND a.surface_frame_token>0;
SELECT json_object(
 'window',(SELECT json_object('ts',ts,'end',ts_end,'ms',(ts_end-ts)/1e6) FROM window),
 'frames',(SELECT json_group_array(json_object('id',id,'ts',ts,'wallMs',dur/1e6,'token',token,'jank',jank_type,'present',present_type,'budgetMs',budget/1e6,'overrunMs',overrun/1e6,'mainMs',main_ms,'renderMs',render_ms)) FROM frames),
 'effects',(SELECT json_group_array(json_object('name',name,'thread',thread_name,'n',n,'ms',ms,'maxMs',max_ms)) FROM (
 SELECT name,thread_name,count(*) n,sum(dur)/1e6 ms,max(dur)/1e6 max_ms FROM app
 WHERE name IN ('Compose:recompose','AndroidOwner:measureAndLayout','HazeSource.record','flush layers','CreateGraphicsPipeline-CompileAfterCacheMiss','Record View#draw()','waitForever')
  OR name GLOB '*GC*' OR name GLOB '*suspend all*'
 GROUP BY name,thread_name)),
 'jankSlices',(SELECT json_group_array(json_object('frame',token,'thread',thread_name,'name',name,'startMs',start_ms,'wallMs',ms)) FROM (
 SELECT f.token,a.thread_name,a.name,(a.ts-f.ts)/1e6 start_ms,a.dur/1e6 ms FROM app a JOIN frames f
 ON a.ts < f.ts+f.dur AND a.ts+a.dur>f.ts
 WHERE f.jank_type LIKE '%Deadline Missed%' AND (a.is_main_thread OR a.thread_name='RenderThread') AND a.dur>0.5e6
 ORDER BY f.token,a.dur DESC LIMIT 100))
) AS result;
