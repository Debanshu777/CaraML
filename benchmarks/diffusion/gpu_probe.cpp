// Diagnostic only: run the pinned C API without the app's Vulkan CLIP/VAE overrides.
#include "stable-diffusion.h"
#include "ggml.h"
#include "ggml-backend.h"
#define STB_IMAGE_WRITE_STATIC
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <map>
#include <string>

static std::map<std::string, size_t> placements;
static bool observe(struct ggml_tensor* tensor, bool ask, void*) {
    if (!ask) return true;
    auto buffer = tensor->buffer;
    if (!buffer && tensor->view_src) buffer = tensor->view_src->buffer;
    auto device = buffer ? ggml_backend_buft_get_device(ggml_backend_buffer_get_type(buffer)) : nullptr;
    placements[device ? ggml_backend_dev_name(device) : "unknown"]++;
    return false; // No tensor reads or per-node GPU synchronization.
}

int main(int argc, char** argv) {
    if (argc < 7 || argc > 9) {
        std::fprintf(stderr, "Usage: gpu-probe LIB_DIR MODEL OUTPUT BACKEND STEPS SIZE [VAE_DIRECT] [ALL_FLASH]\n");
        return 2;
    }
    setvbuf(stdout, nullptr, _IOLBF, 0);
    ggml_backend_load_all_from_path(argv[1]);
    sd_set_log_callback([](sd_log_level_t, const char* text, void*) { std::fputs(text, stdout); }, nullptr);
    sd_set_backend_eval_callback(observe, nullptr);
    sd_ctx_params_t context;
    sd_ctx_params_init(&context);
    context.model_path = argv[2];
    context.backend = argv[4];
    context.params_backend = "cpu";
    context.n_threads = 4;
    context.prediction = EPS_PRED;
    context.diffusion_flash_attn = true;
    context.vae_conv_direct = argc > 7 && std::atoi(argv[7]) == 1;
    context.flash_attn = argc > 8 && std::atoi(argv[8]) == 1;
    context.enable_mmap = true;
    context.auto_fit = false;
    auto ctx = new_sd_ctx(&context);
    if (!ctx) return 3;
    sd_img_gen_params_t request;
    sd_img_gen_params_init(&request);
    request.prompt = "A red ceramic teapot on a wooden table by a window, soft morning light, detailed photograph";
    request.width = request.height = std::atoi(argv[6]);
    request.seed = 42;
    request.batch_count = 1;
    request.sample_params.sample_steps = std::atoi(argv[5]);
    request.sample_params.guidance.txt_cfg = 1.0f;
    request.sample_params.sample_method = EULER_SAMPLE_METHOD;
    request.sample_params.scheduler = SIMPLE_SCHEDULER;
    auto start = std::chrono::steady_clock::now();
    sd_image_t* images = nullptr;
    int count = 0;
    bool ok = generate_image(ctx, &request, &images, &count);
    double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
    if (ok && count > 0 && images && images[0].data) {
        ok = stbi_write_png(argv[3], images[0].width, images[0].height, images[0].channel,
                            images[0].data, images[0].width * images[0].channel) != 0;
    } else ok = false;
    for (const auto& placement : placements) {
        std::printf("EVALUATED_GRAPH_NODES device=%s count=%zu\n", placement.first.c_str(), placement.second);
    }
    std::printf("PROBE_RESULT success=%d seconds=%.3f\n", ok, seconds);
    for (int i = 0; images && i < count; ++i) std::free(images[i].data);
    std::free(images);
    free_sd_ctx(ctx);
    return ok ? 0 : 4;
}
