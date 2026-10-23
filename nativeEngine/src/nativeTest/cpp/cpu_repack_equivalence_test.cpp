// Included after the actual reference/candidate functions by the Python runner.
#include <cstdint>
#include <cstdio>
#include <random>
#include <vector>

template <typename Input, typename Output, typename Reference, typename Candidate>
static size_t check_layout(ggml_type type, Reference reference, Candidate candidate) {
    std::mt19937_64 random(0xc4a6a1);
    size_t cases = 0;
    for (int rows : {8, 16, 24}) {
        for (int blocks : {1, 2, 7, 13}) {
            for (int planes : {1, 2}) {
                for (int pattern = 0; pattern < 68; ++pattern) {
                    std::vector<Input> input(rows * blocks * planes);
                    auto * bytes = reinterpret_cast<uint8_t *>(input.data());
                    for (size_t i = 0; i < input.size() * sizeof(Input); ++i) {
                        bytes[i] = pattern == 0 ? 0 : pattern == 1 ? 255 : pattern == 2 ? i % 256 :
                            pattern == 3 ? (i % 2 ? 0x55 : 0xaa) : static_cast<uint8_t>(random());
                    }
                    const auto original = input;
                    const size_t outputs = input.size() / 8;
                    std::vector<Output> expected(outputs + 2), actual(outputs + 2);
                    std::memset(expected.data(), 0xa5, expected.size() * sizeof(Output));
                    std::memset(actual.data(), 0xa5, actual.size() * sizeof(Output));
                    ggml_tensor tensor{};
                    tensor.type = type;
                    tensor.ne[0] = QK_K * blocks;
                    tensor.ne[1] = rows;
                    tensor.ne[2] = planes;
                    tensor.ne[3] = 1;
                    tensor.data = expected.data() + 1;
                    assert(reference(&tensor, input.data(), input.size() * sizeof(Input)) == 0);
                    tensor.data = actual.data() + 1;
                    assert(candidate(&tensor, input.data(), input.size() * sizeof(Input)) == 0);
                    // Compare every output byte, including quantized scales and
                    // both sentinels. Repacking must leave the input untouched.
                    assert(std::memcmp(expected.data(), actual.data(), expected.size() * sizeof(Output)) == 0);
                    assert(std::memcmp(input.data(), original.data(), input.size() * sizeof(Input)) == 0);
                    ++cases;
                }
            }
        }
    }
    // The existing unsupported row shape must still fail without writing output.
    std::vector<Input> input(7);
    Output output;
    std::memset(&output, 0xa5, sizeof(output));
    const Output before = output;
    ggml_tensor invalid{};
    invalid.type = type;
    invalid.ne[0] = QK_K;
    invalid.ne[1] = 7;
    invalid.ne[2] = invalid.ne[3] = 1;
    invalid.data = &output;
    assert(reference(&invalid, input.data(), input.size() * sizeof(Input)) == -1);
    assert(candidate(&invalid, input.data(), input.size() * sizeof(Input)) == -1);
    assert(std::memcmp(&before, &output, sizeof(output)) == 0);
    return cases + 1;
}

int main() {
    size_t cases = 0;
    cases += check_layout<block_q4_K, block_q4_Kx8>(GGML_TYPE_Q4_K,
        [](ggml_tensor * t, const void * data, size_t bytes) { return reference::repack_q4_K_to_q4_K_8_bl(t, 4, data, bytes); },
        candidate::repack_q4_K_to_q4_K_8_bl<4>);
    cases += check_layout<block_q4_K, block_q4_Kx8>(GGML_TYPE_Q4_K,
        [](ggml_tensor * t, const void * data, size_t bytes) { return reference::repack_q4_K_to_q4_K_8_bl(t, 8, data, bytes); },
        candidate::repack_q4_K_to_q4_K_8_bl<8>);
    cases += check_layout<block_q6_K, block_q6_Kx8>(GGML_TYPE_Q6_K,
        [](ggml_tensor * t, const void * data, size_t bytes) { return reference::repack_q6_K_to_q6_K_8_bl(t, 4, data, bytes); },
        candidate::repack_q6_K_to_q6_K_8_bl<4>);
    cases += check_layout<block_q6_K, block_q6_Kx8>(GGML_TYPE_Q6_K,
        [](ggml_tensor * t, const void * data, size_t bytes) { return reference::repack_q6_K_to_q6_K_8_bl(t, 8, data, bytes); },
        candidate::repack_q6_K_to_q6_K_8_bl<8>);
    std::printf("PASS: %zu byte-identical Q4_K/Q6_K repack cases (4/8-byte layouts)\n", cases);
}
