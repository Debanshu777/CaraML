#include "artifact_fs_jni.cpp"

#include <cstdlib>
#include <fstream>
#include <iostream>
#include <string>

int main() {
    static_assert(kAppendExisting == 3, "append mode must remain stable for JNI callers");
    char temporary[] = "/private/tmp/caraml-artifact-append-XXXXXX";
    const char* root_value = mkdtemp(temporary);
    if (root_value == nullptr) return 1;
    const std::string root(root_value);
    const std::string models = root + "/models";
    const std::string owner = models + "/owner";
    const std::string model = owner + "/model";
    mkdir(models.c_str(), 0700);
    mkdir(owner.c_str(), 0700);
    mkdir(model.c_str(), 0700);
    const std::string file = model + "/artifact.part";
    { std::ofstream output(file, std::ios::binary); output << "first"; }

    RootHandle handle;
    handle.descriptor = open(model.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    handle.models_root = models;
    handle.model_segments = {"owner", "model"};
    struct stat metadata {};
    if (handle.descriptor < 0 || fstat(handle.descriptor, &metadata) != 0) return 1;
    handle.device = metadata.st_dev;
    handle.inode = metadata.st_ino;

    const int descriptor = open_file(&handle, "artifact.part", O_WRONLY | O_APPEND);
    if (descriptor < 0 || write(descriptor, "-second", 7) != 7 || close(descriptor) != 0) return 1;
    std::ifstream input(file, std::ios::binary);
    const std::string content((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
    if (content != "first-second") {
        std::cerr << "append replaced or corrupted existing bytes\n";
        return 1;
    }

    unlink(file.c_str());
    rmdir(model.c_str());
    rmdir(owner.c_str());
    rmdir(models.c_str());
    rmdir(root.c_str());
    return 0;
}
