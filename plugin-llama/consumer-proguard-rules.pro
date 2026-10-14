# JNI looks up these fields by their source names and descriptors.
-keepclassmembers,includedescriptorclasses class com.debanshu777.runner.NativeRunnerConfig {
    int nCtx;
    int nCtxMin;
    int nThreads;
    int nThreadsBatch;
    int nBatch;
    int nUbatch;
    int nOutputsMaxPerSequence;
    int flashAttn;
    boolean offloadKqv;
    int typeK;
    int typeV;
    int nGpuLayers;
    boolean useMmap;
    boolean useMlock;
    float temperature;
    boolean autoFit;
    com.debanshu777.runner.LlamaLazyMode lazyMode;
    java.lang.String cpuMask;
    java.lang.String cpuMaskBatch;
}

-keepclassmembers enum com.debanshu777.runner.LlamaLazyMode {
    int nativeValue;
}
