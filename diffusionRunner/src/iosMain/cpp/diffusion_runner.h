#pragma once

#ifdef __cplusplus
extern "C" {
#endif

struct DiffusionModelConfigFFI {
    const char *model_path;
    const char *vae_path;
    const char *llm_path;
    const char *clip_l_path;
    const char *clip_g_path;
    const char *t5xxl_path;
    int offload_to_cpu;
    int keep_clip_on_cpu;
    int keep_vae_on_cpu;
    int diffusion_flash_attn;
    int enable_mmap;
    int diffusion_conv_direct;
    int free_params_immediately;
    int wtype;
    float flow_shift;
    int flow_shift_is_set;
    int n_threads;
    /** -1=auto-detect, 0=EPS, 1=V_PRED, 2=EDM_V_PRED, 3=FLOW, 4=FLUX_FLOW, 5=FLUX2_FLOW */
    int prediction;
    /** Optional path to TAESD safetensors. Empty string = disabled. */
    const char *taesd_path;
    /** Enable VAE tiling (1=on, 0=off). Library auto-picks tile sizes when on. */
    int vae_tiling;
};

struct ImageGenConfigFFI {
    const char *prompt;
    const char *negative_prompt;
    int width;
    int height;
    int steps;
    float cfg_scale;
    long long seed;
    int sample_method;
};

struct PngResultFFI {
    unsigned char *data;
    int size;
};

void diffusion_runner_ios_init(void);
long long diffusion_runner_ios_load_model(struct DiffusionModelConfigFFI config);
struct PngResultFFI *diffusion_runner_ios_txt2img(
        long long handle,
        struct ImageGenConfigFFI config,
        const char **lora_paths,
        float *lora_strengths,
        int lora_count
);
void diffusion_runner_ios_free_png(unsigned char *data);
void diffusion_runner_ios_free_result(struct PngResultFFI *result);
void diffusion_runner_ios_release(long long handle);

struct DiffusionMetadataResultFFI {
    char architecture[64];
    char dominant_quant[32];
    long long estimated_ram;
    int success;
};

struct DiffusionMetadataResultFFI diffusion_runner_ios_get_metadata(const char* model_path);

#ifdef __cplusplus
}
#endif