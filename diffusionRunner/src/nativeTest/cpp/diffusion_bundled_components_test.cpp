#include "diffusion_runner_preflight_internal.h"

#include <stdexcept>
#include <string>
#include <vector>

namespace {

void expect(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

const caraml::diffusion::PreflightComponentEvidence &component(
        const std::vector<caraml::diffusion::PreflightComponentEvidence> &components,
        int role) {
    for (const auto &candidate : components) {
        if (candidate.subdivision_role == role) return candidate;
    }
    throw std::runtime_error("missing component role");
}

} // namespace

int main() {
    using namespace caraml::diffusion;
    expect(declared_source_subdivision_role(DIFFUSION_COMPONENT_TAESD) ==
            DIFFUSION_COMPONENT_VAE,
           "TAESD source must use the VAE runtime subdivision");
    expect(declared_source_subdivision_role(DIFFUSION_COMPONENT_CLIP_L) ==
            DIFFUSION_COMPONENT_CLIP_L,
           "ordinary split sources must preserve their role");
    const std::vector<PreflightTensorEvidence> bundled_fixture = {
        {"model.diffusion_model.input.weight", 1'000},
        {"text_encoders.clip_l.transformer.weight", 2'000},
        {"first_stage_model.decoder.weight", 3'000},
        {"model_ema.decay", 400},
    };
    const std::vector<PreflightComponentEvidence> components =
        classify_bundled_components(
            bundled_fixture,
            DIFFUSION_COMPONENT_MODEL_BUNDLE,
            0,
            "diffusion=metal0,te=cpu,vae=metal0&metal1",
            "diffusion=cpu,te=disk,vae=cpu",
            [](const std::string &assignment) {
                if (assignment == "metal0") return int64_t{1};
                if (assignment == "metal0&metal1") return int64_t{3};
                return int64_t{0};
            });

    expect(components.size() == 4, "bundle roles were not reported independently");
    const auto &diffusion = component(components, DIFFUSION_COMPONENT_DIFFUSION_MODEL);
    const auto &te = component(components, DIFFUSION_COMPONENT_TEXT_ENCODER);
    const auto &vae = component(components, DIFFUSION_COMPONENT_VAE);
    const auto &other = component(components, DIFFUSION_COMPONENT_OTHER);

    for (const auto &evidence : components) {
        expect(evidence.source_role == DIFFUSION_COMPONENT_MODEL_BUNDLE &&
                evidence.source_ordinal == 0,
               "bundle subdivision lost its configured source binding");
    }
    expect(diffusion.parameter_bytes == 1'000, "diffusion bytes were not classified");
    expect(diffusion.runtime_placement == DIFFUSION_RUNTIME_GPU &&
            diffusion.runtime_backend_mask == 1 &&
            diffusion.parameter_placement == DIFFUSION_PARAMS_CPU,
           "diffusion placement does not match the resolved load plan");
    expect(te.parameter_bytes == 2'000 && te.runtime_placement == DIFFUSION_RUNTIME_CPU &&
            te.parameter_placement == DIFFUSION_PARAMS_DISK,
           "text-encoder placement does not match the resolved load plan");
    expect(vae.parameter_bytes == 3'000 && vae.runtime_placement == DIFFUSION_RUNTIME_SPLIT_GPU &&
            vae.runtime_backend_mask == 3 && vae.parameter_placement == DIFFUSION_PARAMS_CPU,
           "VAE placement does not match the resolved load plan");
    expect(other.parameter_bytes == 400 && other.runtime_placement == DIFFUSION_RUNTIME_DEFAULT &&
            other.parameter_placement == DIFFUSION_PARAMS_DEFAULT,
           "unclassified bundle bytes were not retained as other evidence");

    const auto taesd = classify_bundled_components(
        {{"tae.decoder.weight", 500}},
        DIFFUSION_COMPONENT_TAESD,
        1,
        "cpu",
        "vae=cpu",
        [](const std::string &) { return int64_t{0}; });
    expect(taesd.size() == 1 &&
            taesd[0].source_role == DIFFUSION_COMPONENT_TAESD &&
            taesd[0].source_ordinal == 1 &&
            taesd[0].subdivision_role == DIFFUSION_COMPONENT_VAE,
           "external TAESD must remain distinct from bundle-internal VAE evidence");
    return 0;
}
