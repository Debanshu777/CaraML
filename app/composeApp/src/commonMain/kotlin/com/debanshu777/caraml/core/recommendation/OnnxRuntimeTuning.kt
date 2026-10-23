package com.debanshu777.caraml.core.recommendation

/** Measured defaults are scoped to the tested device, runtime and immutable graph.
 * Unknown combinations retain a conservative single-thread baseline. SDK callers
 * can explicitly benchmark other thread counts through OnnxRuntimeConfig.
 */
internal fun onnxCpuThreads(
    files: List<ModelFileIdentity>,
    engineVersion: String,
    deviceModel: String? = onnxDeviceModel(),
): Int {
    val twoThreadGraphs = if (deviceModel == "iPhone14,5" && engineVersion.startsWith("onnxruntime-1.30.0-")) setOf(
        "0fab87142e3eb1fcacb881f8282e7473e62ad66920c347b81f088da6fda2da37", // SmolLM2 135M INT8
        "5ee6c8dc6791050d8779687a16c8167643a234e00ea41e06a92e05f88003dbb3", // SmolLM2 360M INT8
        "d93222c672992a398eeea3a4a1ca025738efd9a18ba5f1691419ff8ae23a8e75", // Qwen3 0.6B INT8
    ) else if (deviceModel == "Pixel 9" && engineVersion.startsWith("onnxruntime-1.31.0-")) setOf(
        "5ee6c8dc6791050d8779687a16c8167643a234e00ea41e06a92e05f88003dbb3", // SmolLM2 360M INT8
        "41834041ab1b29eff9fc592f1a29a1844133aea35832ea9fa91682be13016100", // Qwen2.5 0.5B INT8
        "d93222c672992a398eeea3a4a1ca025738efd9a18ba5f1691419ff8ae23a8e75", // Qwen3 0.6B INT8
        "a3a6f10916f79379d15cfa9270b7be0d09be2b80fe0872bd7030eaf9001baf46", // Llama 3.2 1B Q4
    ) else emptySet()
    return if (files.any { it.lfsOid?.removePrefix("sha256:")?.lowercase() in twoThreadGraphs }) 2 else 1
}
