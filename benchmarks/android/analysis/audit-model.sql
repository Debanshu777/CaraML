CREATE PERFETTO TABLE app_threads AS SELECT t.* FROM thread t JOIN process p USING(upid) WHERE p.name='com.debanshu777.caraml';
CREATE PERFETTO TABLE phases AS SELECT s.*,tt.utid FROM slice s JOIN thread_track tt ON tt.id=s.track_id JOIN app_threads t USING(utid)
 WHERE s.name IN ('CaraML.engineInitialize','CaraML.nativeModelLoad') AND s.dur>0;
SELECT json_object(
 'phases',(SELECT json_group_array(json_object('id',id,'name',name,'ts',ts,'wallMs',dur/1e6)) FROM phases),
 'callerStates',(SELECT json_group_array(json_object('phase',name,'state',state,'ioWait',io_wait,'blocked',blocked_function,'ms',ms)) FROM (
 SELECT a.name,st.state,st.io_wait,st.blocked_function,SUM(MIN(st.ts+st.dur,a.ts+a.dur)-MAX(st.ts,a.ts))/1e6 ms FROM phases a JOIN thread_state st USING(utid)
 WHERE st.dur>0 AND st.ts<a.ts+a.dur AND st.ts+st.dur>a.ts GROUP BY a.name,st.state,st.io_wait,st.blocked_function)),
 'cpuByThread',(SELECT json_group_array(json_object('phase',phase,'thread',name,'tid',tid,'ms',ms)) FROM (
 SELECT a.name phase,t.name,t.tid,SUM(MIN(st.ts+st.dur,a.ts+a.dur)-MAX(st.ts,a.ts))/1e6 ms FROM phases a,thread_state st JOIN app_threads t ON t.utid=st.utid
 WHERE st.state='Running' AND st.dur>0 AND st.ts<a.ts+a.dur AND st.ts+st.dur>a.ts GROUP BY a.id,t.utid ORDER BY ms DESC LIMIT 30)),
 'cpuByCore',(SELECT json_group_array(json_object('phase',name,'cpu',cpu,'ms',ms)) FROM (
 SELECT a.name,st.cpu,SUM(MIN(st.ts+st.dur,a.ts+a.dur)-MAX(st.ts,a.ts))/1e6 ms FROM phases a,thread_state st JOIN app_threads t ON t.utid=st.utid
 WHERE st.state='Running' AND st.dur>0 AND st.ts<a.ts+a.dur AND st.ts+st.dur>a.ts GROUP BY a.id,st.cpu)),
 'nestedSlices',(SELECT json_group_array(json_object('phase',phase,'name',name,'n',n,'ms',ms)) FROM (
 SELECT a.name phase,s.name,count(*) n,sum(s.dur)/1e6 ms FROM phases a JOIN slice s ON s.ts>=a.ts AND s.ts+s.dur<=a.ts+a.dur
 JOIN thread_track tt ON tt.id=s.track_id AND tt.utid=a.utid WHERE s.id!=a.id AND s.dur>0 GROUP BY a.name,s.name ORDER BY ms DESC LIMIT 30))
) AS result;
