#include "scoped_context_publication.h"

#include <memory>
#include <stdexcept>

namespace {

struct FakeContext {};
struct FakeHandle { FakeContext *context = nullptr; };

void expect(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

} // namespace

int main() {
    int frees = 0;
    const auto free_context = [&](FakeContext *context) {
        if (context) {
            ++frees;
            delete context;
        }
    };

    try {
        caraml::publish_owned_context(
            new FakeContext,
            free_context,
            [](FakeContext *) -> std::shared_ptr<FakeHandle> {
                throw std::bad_alloc();
            },
            [](const std::shared_ptr<FakeHandle> &) { return 1; });
    } catch (const std::bad_alloc &) {
    }
    expect(frees == 1, "allocation failure did not free the native context exactly once");

    try {
        caraml::publish_owned_context(
            new FakeContext,
            free_context,
            [](FakeContext *context) {
                auto handle = std::make_shared<FakeHandle>();
                handle->context = context;
                return handle;
            },
            [](const std::shared_ptr<FakeHandle> &) -> int {
                throw std::runtime_error("publication fault");
            });
    } catch (const std::runtime_error &) {
    }
    expect(frees == 2, "publication failure did not free the native context exactly once");

    auto published = caraml::publish_owned_context(
        new FakeContext,
        free_context,
        [](FakeContext *context) {
            auto handle = std::make_shared<FakeHandle>();
            handle->context = context;
            return handle;
        },
        [](const std::shared_ptr<FakeHandle> &handle) { return handle; });
    expect(frees == 2 && published->context != nullptr,
           "successful publication released native ownership too early");
    free_context(published->context);
    published->context = nullptr;
    expect(frees == 3, "successful publication did not transfer one ownership");
    return 0;
}
