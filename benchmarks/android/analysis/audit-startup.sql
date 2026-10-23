INCLUDE PERFETTO MODULE android.startup.startups;
CREATE PERFETTO TABLE launch AS SELECT * FROM android_startups
 WHERE package='com.debanshu777.caraml.benchmark' ORDER BY ts DESC LIMIT 1;
CREATE PERFETTO TABLE app AS
 SELECT s.*,t.name thread_name,t.is_main_thread,t.utid,
        MIN(s.ts+s.dur,l.ts_end)-MAX(s.ts,l.ts) clipped_dur
 FROM slice s JOIN thread_track tt ON tt.id=s.track_id JOIN thread t USING(utid)
 JOIN process p USING(upid),launch l
 WHERE p.name=l.package AND s.dur>0 AND s.ts<l.ts_end AND s.ts+s.dur>l.ts;
CREATE PERFETTO TABLE main_long AS
 SELECT a.*, parent.name parent_name,
 (SELECT SUM(MIN(st.ts+st.dur,a.ts+a.dur)-MAX(st.ts,a.ts))/1e6 FROM thread_state st
  WHERE st.utid=a.utid AND st.state='Running' AND st.dur>0 AND st.ts<a.ts+a.dur AND st.ts+st.dur>a.ts) cpu_ms
 FROM app a LEFT JOIN slice parent ON parent.id=a.parent_id
 WHERE a.is_main_thread AND (a.dur>3e6 OR a.name GLOB 'com.debanshu777.caraml*');
SELECT json_object(
 'launch', (SELECT json_object('ts',ts,'end',ts_end,'ms',dur/1e6) FROM launch),
 'threads', (SELECT json_group_array(json_object('thread',thread_name,'state',state,'ioWait',io_wait,'ms',ms,'maxMs',max_ms)) FROM (
  SELECT t.name thread_name,st.state,st.io_wait,SUM(MIN(st.ts+st.dur,l.ts_end)-MAX(st.ts,l.ts))/1e6 ms,
   MAX(MIN(st.ts+st.dur,l.ts_end)-MAX(st.ts,l.ts))/1e6 max_ms
  FROM thread_state st JOIN thread t USING(utid) JOIN process p USING(upid),launch l
  WHERE p.name=l.package AND (t.is_main_thread OR t.name='RenderThread') AND st.dur>0
   AND st.ts<l.ts_end AND st.ts+st.dur>l.ts GROUP BY t.name,st.state,st.io_wait)),
 'mainLong', (SELECT json_group_array(json_object('id',id,'name',name,'parent',parent_name,
  'startMs',(ts-(SELECT ts FROM launch))/1e6,'wallMs',dur/1e6,'cpuMs',cpu_ms))
  FROM (SELECT * FROM main_long ORDER BY dur DESC LIMIT 35)),
 'renderTop', (SELECT json_group_array(json_object('name',name,'calls',n,'wallMs',ms,'maxMs',max_ms)) FROM (
  SELECT name,count(*) n,sum(clipped_dur)/1e6 ms,max(dur)/1e6 max_ms FROM app WHERE thread_name='RenderThread'
  GROUP BY name ORDER BY ms DESC LIMIT 20)),
 'composition', (SELECT json_group_array(json_object('name',name,'calls',n,'wallMs',ms,'cpuMs',cpu_ms)) FROM (
  SELECT name,count(*) n,sum(dur)/1e6 ms,sum(cpu_ms) cpu_ms FROM main_long
  WHERE name GLOB 'com.debanshu777.caraml*' GROUP BY name ORDER BY ms DESC LIMIT 20)),
 'blocking', (SELECT json_group_array(json_object('state',state,'ioWait',io_wait,'function',blocked_function,'ms',ms)) FROM (
  SELECT st.state,st.io_wait,st.blocked_function,SUM(MIN(st.ts+st.dur,l.ts_end)-MAX(st.ts,l.ts))/1e6 ms
  FROM thread_state st JOIN thread t USING(utid) JOIN process p USING(upid),launch l
  WHERE p.name=l.package AND t.is_main_thread AND st.state='D' AND st.dur>0
   AND st.ts<l.ts_end AND st.ts+st.dur>l.ts GROUP BY st.state,st.io_wait,st.blocked_function ORDER BY ms DESC LIMIT 10)),
 'phases',(SELECT json_group_array(json_object('name',label,'calls',n,'wallMs',ms)) FROM (
 SELECT CASE WHEN name GLOB 'OpenDexFilesFromOat*' THEN 'OpenDexFilesFromOat'
 WHEN name GLOB 'Verify dex file*' THEN 'Verify dex file' ELSE name END label,
 count(*) n,sum(clipped_dur)/1e6 ms FROM app WHERE is_main_thread AND (
 name IN ('bindApplication','AppImage:Loading','Compose:recompose','AndroidOwner:onMeasure','AndroidOwner:onLayout','postAndWait')
 OR name GLOB 'OpenDexFilesFromOat*' OR name GLOB 'Verify dex file*') GROUP BY label)),
 'gc', (SELECT json_group_array(json_object('thread',thread_name,'name',name,'wallMs',dur/1e6)) FROM app
  WHERE name GLOB '*GC*' OR name GLOB '*suspend all*')
) AS result;
