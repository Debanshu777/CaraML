#include "diffusion_sampling_progress.h"
#include <cstdlib>
#include <iostream>

int main() {
    caraml::diffusion::SamplingProgress progress;
    auto expect = [&](int step, int total) {
        if (progress.read() != std::make_pair(step, total)) {
            std::cerr << "Unexpected sampling progress\n";
            std::exit(1);
        }
    };
    progress.begin(4);
    progress.observe(372, 372); // Text encoder load.
    progress.observe(686, 686); // UNet load.
    expect(0, 0);
    progress.observe(0, 4);
    expect(0, 4);
    progress.observe(1, 4);
    expect(1, 4);
    progress.observe(-1, 4);
    progress.observe(5, 4);
    progress.observe(0, 4);
    expect(1, 4);
    progress.observe(4, 4);
    progress.observe(140, 140); // VAE load.
    progress.observe(1, 4); // Four VAE tiles must not restart completed sampling.
    expect(4, 4);
    progress.end();
    progress.observe(4, 4);
    expect(0, 0);
    progress.begin(1);
    progress.observe(0, 1);
    progress.observe(1, 1);
    expect(1, 1);
    progress.end();
    expect(0, 0);
    progress.begin(20, false); // Video splits a 20-step request into two stages.
    progress.observe(1, 5);
    expect(1, 5);
    progress.observe(5, 5);
    progress.observe(0, 15);
    expect(0, 15);
    progress.observe(15, 15);
    expect(15, 15);
    progress.end();
    expect(0, 0);
}
