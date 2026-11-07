-- Every file is checked separately; no nonzero loss stats does not prove missing sources were enabled.
SELECT json_object(
  'durationMs', (SELECT (end_ts-start_ts)/1e6 FROM trace_bounds),
  'errors', (SELECT json_group_array(json_object('name',name,'index',idx,'value',value,'severity',severity))
             FROM stats WHERE value>0 AND severity IN ('error','data_loss')),
  'cpuSamples', (SELECT count(*) FROM perf_sample),
  'heapAllocations', (SELECT count(*) FROM heap_profile_allocation),
  'gpuSlices', (SELECT count(*) FROM gpu_slice),
  'appIncompleteSlices', (SELECT count(*) FROM slice s JOIN thread_track tt ON tt.id=s.track_id
                         JOIN thread t USING(utid) JOIN process p USING(upid)
                         WHERE p.name IN ('com.debanshu777.caraml.benchmark','com.debanshu777.caraml') AND s.dur=-1),
  'appFrameRows', (SELECT count(*) FROM actual_frame_timeline_slice a JOIN process p USING(upid)
                  WHERE p.name IN ('com.debanshu777.caraml.benchmark','com.debanshu777.caraml'))
) AS result;
