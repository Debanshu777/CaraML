#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct CaramlOnnx CaramlOnnx;
CaramlOnnx* caraml_onnx_create(void);
void caraml_onnx_destroy(CaramlOnnx* handle);
int caraml_onnx_load(CaramlOnnx* handle, const char* path, int threads, int coreml, int kv_heads, int head_dim, int low_memory);
void caraml_onnx_unload(CaramlOnnx* handle);
void caraml_onnx_cancel(CaramlOnnx* handle);
void caraml_onnx_begin(CaramlOnnx* handle);
int caraml_onnx_step(CaramlOnnx* handle, const int64_t* tokens, int count, int past_length);
int caraml_onnx_has_cache(CaramlOnnx* handle);
int caraml_onnx_vocab_size(CaramlOnnx* handle);
const float* caraml_onnx_scores(CaramlOnnx* handle);
const char* caraml_onnx_error(CaramlOnnx* handle);
const char* caraml_onnx_description(CaramlOnnx* handle);
const char* caraml_onnx_version(void);
#ifdef __cplusplus
}
#endif
