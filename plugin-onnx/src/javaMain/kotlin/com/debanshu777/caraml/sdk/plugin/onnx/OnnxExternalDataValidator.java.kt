package com.debanshu777.caraml.sdk.plugin.onnx

import java.io.File

internal fun validateOnnxExternalData(graph: File) = validateOnnxGraph(graph.path)
