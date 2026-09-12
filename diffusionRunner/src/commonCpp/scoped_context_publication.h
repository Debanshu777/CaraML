#pragma once

#include <memory>
#include <utility>

namespace caraml {

template <typename Context, typename Deleter, typename Factory, typename Publisher>
auto publish_owned_context(
        Context *context,
        Deleter deleter,
        Factory factory,
        Publisher publisher) {
    std::unique_ptr<Context, Deleter> owner(context, deleter);
    auto handle = factory(owner.get());
    auto published = publisher(handle);
    owner.release();
    return published;
}

} // namespace caraml
