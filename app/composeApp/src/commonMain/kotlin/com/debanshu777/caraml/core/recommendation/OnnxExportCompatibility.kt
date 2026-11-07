package com.debanshu777.caraml.core.recommendation

/** Exact-artifact exclusions from device validation, not architecture blacklists.
 * Pixel INT8 has a quality failure; the larger Q4 export cannot load on iPhone 13.
 * Revisit when changing ONNX Runtime or the export. See benchmarks/onnx/README.md. */
internal fun onnxExportIssue(files: List<ModelFileIdentity>, deviceModel: String? = onnxDeviceModel()): String? {
    if (deviceModel == "iPhone14,5" && files.any { it.lfsOid?.removePrefix("sha256:")?.lowercase() ==
            "a3a6f10916f79379d15cfa9270b7be0d09be2b80fe0872bd7030eaf9001baf46" }) {
        return "This Llama Q4 ONNX export could not load on iPhone 13. Choose the GGUF version or a smaller ONNX model."
    }
    if (deviceModel != "Pixel 9") return null
    return if (files.any { it.lfsOid?.removePrefix("sha256:")?.lowercase() ==
            "ad53ed44994c27ab60e8024e83fcd83ce38db4d4ad3a28549b432480eb84d180" }) {
        "This Llama INT8 export produced incorrect answers in Pixel 9 validation. Choose model_q4.onnx instead."
    } else null
}

internal expect fun onnxDeviceModel(): String?
