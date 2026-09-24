#define ARTIFACT_FS_ANDROID_DIRECT_OPEN_TEST 1
#include "artifact_fs_jni.cpp"

#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <iostream>
#include <string>
#include <vector>

namespace {

bool make_directory(const std::string& path, mode_t mode) {
    return mkdir(path.c_str(), mode) == 0;
}

}  // namespace

int main() {
    std::error_code temporary_error;
    const std::filesystem::path temporary_root = std::filesystem::temp_directory_path(temporary_error);
    if (temporary_error || temporary_root.empty()) {
        std::cerr << "failed to resolve test temporary directory: " << temporary_error.message() << "\n";
        return 1;
    }
    const std::string temporary_pattern =
        (temporary_root / "caraml-artifact-fs-XXXXXX").string();
    std::vector<char> temporary(temporary_pattern.begin(), temporary_pattern.end());
    temporary.push_back('\0');
    const char* root_value = mkdtemp(temporary.data());
    if (root_value == nullptr) {
        std::cerr << "failed to create test root: " << std::strerror(errno) << "\n";
        return 1;
    }

    const std::string root(root_value);
    const std::string searchable_parent = root + "/search-only";
    const std::string models_root = searchable_parent + "/models";
    if (!make_directory(searchable_parent, 0700) || !make_directory(models_root, 0700) ||
        chmod(searchable_parent.c_str(), 0111) != 0) {
        std::cerr << "failed to prepare restricted directory fixture\n";
        return 1;
    }

    const int descriptor = open_absolute_directory(models_root, false);
    const int saved_errno = errno;
    chmod(searchable_parent.c_str(), 0700);

    const std::string outside_root = root + "/outside";
    const std::string linked_models_root = searchable_parent + "/models-link";
    const bool symlink_fixture_ready =
        make_directory(outside_root, 0700) &&
        symlink(outside_root.c_str(), linked_models_root.c_str()) == 0;
    const int symlink_descriptor =
        symlink_fixture_ready ? open_absolute_directory(linked_models_root, false) : -1;

    if (descriptor >= 0) close(descriptor);
    if (symlink_descriptor >= 0) close(symlink_descriptor);
    unlink(linked_models_root.c_str());
    rmdir(outside_root.c_str());
    rmdir(models_root.c_str());
    rmdir(searchable_parent.c_str());
    rmdir(root.c_str());

    if (descriptor < 0) {
        std::cerr << "trusted app root required read access to an ancestor: errno=" << saved_errno << "\n";
        return 1;
    }
    if (!symlink_fixture_ready) {
        std::cerr << "failed to prepare symlink fixture\n";
        return 1;
    }
    if (symlink_descriptor >= 0) {
        std::cerr << "trusted app root accepted a symlinked final directory\n";
        return 1;
    }
    return 0;
}
