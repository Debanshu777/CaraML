// Link against the same pinned sd.cpp and GGML static libraries as the app.
#include "stable-diffusion.h"
#include <cstdio>

int main(int argc, char** argv) {
    if (argc != 3) {
        std::fprintf(stderr, "Usage: convert-model INPUT OUTPUT-Q4_0.gguf\n");
        return 2;
    }
    return convert(argv[1], "", argv[2], SD_TYPE_Q4_0, "", false) ? 0 : 1;
}
