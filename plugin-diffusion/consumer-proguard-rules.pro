# JNI resolves these configuration fields and enum getters by their source names.
-keepclassmembers,includedescriptorclasses class com.debanshu777.diffusionrunner.DiffusionModelConfig {
    java.lang.String modelPath;
    java.lang.String vaePath;
    java.lang.String llmPath;
    java.lang.String clipLPath;
    java.lang.String clipGPath;
    java.lang.String t5xxlPath;
    com.debanshu777.diffusionrunner.DiffusionRuntimeBackend runtimeBackend;
    boolean offloadToCpu;
    boolean keepClipOnCpu;
    boolean keepVaeOnCpu;
    boolean diffusionFlashAttn;
    boolean enableMmap;
    boolean diffusionConvDirect;
    boolean freeParamsImmediately;
    int wtype;
    float flowShift;
    int nThreads;
    int prediction;
    java.lang.String taesdPath;
    boolean vaeTiling;
    java.lang.String maxVram;
    boolean segmentedCompute;
    boolean prefetch;
    boolean autoFit;
}

-keepclassmembers enum com.debanshu777.diffusionrunner.DiffusionRuntimeBackend {
    int getNativeValue();
}

# Native metadata construction uses this exact class name and constructor signature.
-keep class com.debanshu777.diffusionrunner.DiffusionModelMetadata {
    <init>(java.lang.String, java.lang.String, long);
}
