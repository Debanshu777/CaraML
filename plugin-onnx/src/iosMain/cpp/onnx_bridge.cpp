#include "onnx_bridge.h"
#include "onnxruntime_cxx_api.h"
#include <algorithm>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

struct CaramlOnnx {
    Ort::Env env{ORT_LOGGING_LEVEL_WARNING, "CaraML"};
    std::unique_ptr<Ort::Session> session;
    Ort::RunOptions run;
    std::mutex control;
    std::vector<std::string> input_names, output_names;
    std::vector<std::vector<int64_t>> input_shapes;
    std::vector<Ort::Value> previous;
    std::string error, description = "ONNX Runtime (unloaded)";
    size_t logits_index = 0;
    bool cache = false;
    const float* scores = nullptr;
    int vocab = 0;
    CaramlOnnx() { env.DisableTelemetryEvents(); }
};

extern "C" CaramlOnnx* caraml_onnx_create() {
    try { return new CaramlOnnx(); } catch (...) { return nullptr; }
}
extern "C" void caraml_onnx_destroy(CaramlOnnx* handle) { delete handle; }
extern "C" const char* caraml_onnx_version() { return OrtGetApiBase()->GetVersionString(); }
extern "C" const char* caraml_onnx_error(CaramlOnnx* h) { return h->error.c_str(); }
extern "C" const char* caraml_onnx_description(CaramlOnnx* h) { return h->description.c_str(); }
extern "C" int caraml_onnx_has_cache(CaramlOnnx* h) { return h->cache; }
extern "C" int caraml_onnx_vocab_size(CaramlOnnx* h) { return h->vocab; }
extern "C" const float* caraml_onnx_scores(CaramlOnnx* h) { return h->scores; }
extern "C" void caraml_onnx_cancel(CaramlOnnx* h) {
    std::lock_guard<std::mutex> guard(h->control);
    try { h->run.SetTerminate(); } catch (...) {}
}
extern "C" void caraml_onnx_begin(CaramlOnnx* h) {
    std::lock_guard<std::mutex> guard(h->control);
    h->previous.clear(); h->scores = nullptr; h->vocab = 0;
    h->run.UnsetTerminate();
}
extern "C" void caraml_onnx_unload(CaramlOnnx* h) {
    h->previous.clear(); h->scores = nullptr; h->vocab = 0;
    h->session.reset(); h->description = "ONNX Runtime (unloaded)";
}
extern "C" int caraml_onnx_load(CaramlOnnx* h, const char* path, int threads, int coreml, int heads, int dim, int low_memory) {
    try {
        caraml_onnx_unload(h);
        std::string provider = "CPU";
        auto open = [&](bool accelerated) {
            Ort::SessionOptions options;
            options.SetIntraOpNumThreads(threads).SetInterOpNumThreads(1);
            options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
            options.DisableMemPattern();
            if (low_memory) {
                options.DisableCpuMemArena();
                options.AddConfigEntry("session.disable_prepacking", "1");
            }
            options.AddConfigEntry("session.intra_op.allow_spinning", "0");
            if (accelerated) options.AppendExecutionProvider("CoreML", {
                {"ModelFormat", "MLProgram"}, {"MLComputeUnits", "CPUAndGPU"},
                {"RequireStaticInputShapes", "0"}, {"EnableOnSubgraphs", "0"},
            });
            return std::make_unique<Ort::Session>(h->env, path, options);
        };
        h->session = open(false);
        Ort::AllocatorWithDefaultOptions allocator;
        h->input_names.clear(); h->output_names.clear(); h->input_shapes.clear(); h->cache = false;
        for (size_t i = 0; i < h->session->GetOutputCount(); ++i)
            h->output_names.emplace_back(h->session->GetOutputNameAllocated(i, allocator).get());
        const auto logits = std::find(h->output_names.begin(), h->output_names.end(), "logits");
        if (logits == h->output_names.end()) throw std::runtime_error("Decoder is missing logits");
        h->logits_index = static_cast<size_t>(logits - h->output_names.begin());
        for (size_t i = 0; i < h->session->GetInputCount(); ++i) {
            std::string name(h->session->GetInputNameAllocated(i, allocator).get());
            auto type = h->session->GetInputTypeInfo(i);
            auto tensor = type.GetTensorTypeAndShapeInfo();
            auto shape = tensor.GetShape();
            if (name.rfind("past_key_values.", 0) == 0) {
                if (shape.size() != 4 || tensor.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT)
                    throw std::runtime_error("Only rank-4 float32 KV caches are supported");
                std::string output = "present." + name.substr(16);
                if (std::find(h->output_names.begin(), h->output_names.end(), output) == h->output_names.end())
                    throw std::runtime_error("Missing KV output");
                shape = {1, shape[1] > 0 ? shape[1] : heads, 0, shape[3] > 0 ? shape[3] : dim};
                if (shape[1] <= 0 || shape[3] <= 0) throw std::runtime_error("Invalid KV dimensions");
                h->cache = true;
            } else if (name != "input_ids" && name != "attention_mask" && name != "position_ids" && name != "use_cache_branch")
                throw std::runtime_error("Unsupported decoder input");
            h->input_names.push_back(name); h->input_shapes.push_back(shape);
        }
        if (std::find(h->input_names.begin(), h->input_names.end(), "input_ids") == h->input_names.end())
            throw std::runtime_error("Decoder is missing input_ids");
        // Empty past tensors are part of the decoder contract. Padding them changes
        // dynamic quantization and can alter tokens, even with an attention mask.
        // Core ML rejects zero-element inputs, so these exports retain CPU execution.
        if (coreml && h->cache) provider = "CPU; CoreML does not support empty KV inputs";
        else if (coreml) {
            try { h->session = open(true); provider = "CoreML CPUAndGPU (partitioned)"; }
            catch (const Ort::Exception&) { h->session = open(false); provider = "CPU; CoreML initialization failed"; }
        }
        h->description = std::string("ONNX Runtime ") + caraml_onnx_version() + "; " + provider + "; " + std::to_string(threads) + " threads" +
                         (low_memory ? "; low-memory allocation" : "");
        return 1;
    } catch (const std::exception& failure) {
        caraml_onnx_unload(h); h->error = failure.what(); return 0;
    }
}

extern "C" int caraml_onnx_step(CaramlOnnx* h, const int64_t* tokens, int count, int past) {
    try {
        if (!h->session || count <= 0 || past < 0) throw std::runtime_error("Invalid decoder step");
        Ort::AllocatorWithDefaultOptions allocator;
        std::vector<Ort::Value> owned;
        std::vector<const OrtValue*> inputs;
        std::vector<const char*> input_names, output_names;
        owned.reserve(h->input_names.size());
        auto longs = [&](const std::vector<int64_t>& values) -> const OrtValue* {
            const int64_t shape[] = {1, static_cast<int64_t>(values.size())};
            owned.push_back(Ort::Value::CreateTensor<int64_t>(allocator, shape, 2));
            std::copy(values.begin(), values.end(), owned.back().GetTensorMutableData<int64_t>());
            return owned.back();
        };
        for (size_t i = 0; i < h->input_names.size(); ++i) {
            const auto& name = h->input_names[i];
            input_names.push_back(name.c_str());
            if (name == "input_ids") inputs.push_back(longs(std::vector<int64_t>(tokens, tokens + count)));
            else if (name == "attention_mask") inputs.push_back(longs(std::vector<int64_t>(past + count, 1)));
            else if (name == "position_ids") {
                std::vector<int64_t> positions(count);
                for (int j = 0; j < count; ++j) positions[j] = past + j;
                inputs.push_back(longs(positions));
            } else if (name == "use_cache_branch") {
                const int64_t shape[] = {1};
                owned.push_back(Ort::Value::CreateTensor<bool>(allocator, shape, 1));
                *owned.back().GetTensorMutableData<bool>() = !h->previous.empty();
                inputs.push_back(owned.back());
            } else if (h->previous.empty()) {
                const auto& shape = h->input_shapes[i];
                owned.push_back(Ort::Value::CreateTensor<float>(allocator, shape.data(), shape.size()));
                inputs.push_back(owned.back());
            } else {
                const auto output = "present." + name.substr(16);
                const auto found = std::find(h->output_names.begin(), h->output_names.end(), output);
                inputs.push_back(h->previous[static_cast<size_t>(found - h->output_names.begin())]);
            }
        }
        for (const auto& name : h->output_names) output_names.push_back(name.c_str());
        std::vector<OrtValue*> outputs(output_names.size(), nullptr);
        try {
            Ort::ThrowOnError(Ort::GetApi().Run(*h->session, h->run, input_names.data(), inputs.data(), inputs.size(),
                output_names.data(), output_names.size(), outputs.data()));
        } catch (...) {
            for (auto* output : outputs) if (output) Ort::GetApi().ReleaseValue(output);
            throw;
        }
        h->previous.clear();
        for (auto* output : outputs) h->previous.emplace_back(output);
        auto& logits = h->previous[h->logits_index];
        const auto info = logits.GetTensorTypeAndShapeInfo();
        const auto shape = info.GetShape();
        if (info.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT || shape.empty() || shape.back() <= 0)
            throw std::runtime_error("Only float32 logits are supported");
        h->vocab = static_cast<int>(shape.back());
        h->scores = logits.GetTensorData<float>() + info.GetElementCount() - h->vocab;
        return 1;
    } catch (const std::exception& failure) { h->error = failure.what(); h->scores = nullptr; h->vocab = 0; return 0; }
}
