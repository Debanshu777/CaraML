#include <jni.h>

#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#if defined(_WIN32)
#error "artifact_fs Windows handle backend must be provided before Windows builds are supported"
#else
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

namespace {

constexpr std::size_t kMaxPathBytes = 4096;
constexpr jint kReadOnly = 0;
constexpr jint kCreateNew = 1;
constexpr jint kCreateTruncate = 2;
constexpr jint kAppendExisting = 3;

struct RootHandle {
    int descriptor = -1;
    std::string models_root;
    std::vector<std::string> model_segments;
    dev_t device = 0;
    ino_t inode = 0;

    ~RootHandle() {
        if (descriptor >= 0) close(descriptor);
    }
};

bool is_strict_utf8(const std::string& value) {
    for (std::size_t index = 0; index < value.size();) {
        const auto first = static_cast<unsigned char>(value[index]);
        if (first == 0) return false;
        if (first <= 0x7f) { ++index; continue; }
        auto continuation = [&](std::size_t offset) {
            return index + offset < value.size() &&
                (static_cast<unsigned char>(value[index + offset]) & 0xc0) == 0x80;
        };
        if (first >= 0xc2 && first <= 0xdf && continuation(1)) { index += 2; continue; }
        if (first == 0xe0 && continuation(1) && continuation(2) &&
            static_cast<unsigned char>(value[index + 1]) >= 0xa0) { index += 3; continue; }
        if (((first >= 0xe1 && first <= 0xec) || (first >= 0xee && first <= 0xef)) &&
            continuation(1) && continuation(2)) { index += 3; continue; }
        if (first == 0xed && continuation(1) && continuation(2) &&
            static_cast<unsigned char>(value[index + 1]) <= 0x9f) { index += 3; continue; }
        if (first == 0xf0 && continuation(1) && continuation(2) && continuation(3) &&
            static_cast<unsigned char>(value[index + 1]) >= 0x90) { index += 4; continue; }
        if (first >= 0xf1 && first <= 0xf3 && continuation(1) && continuation(2) && continuation(3)) {
            index += 4;
            continue;
        }
        if (first == 0xf4 && continuation(1) && continuation(2) && continuation(3) &&
            static_cast<unsigned char>(value[index + 1]) <= 0x8f) { index += 4; continue; }
        return false;
    }
    return !value.empty();
}

bool from_java(JNIEnv* env, jbyteArray input, std::string* output) {
    if (input == nullptr || output == nullptr) return false;
    const jsize byte_count = env->GetArrayLength(input);
    if (byte_count <= 0 || static_cast<std::size_t>(byte_count) > kMaxPathBytes) return false;
    output->resize(static_cast<std::size_t>(byte_count));
    env->GetByteArrayRegion(input, 0, byte_count, reinterpret_cast<jbyte*>(output->data()));
    return !env->ExceptionCheck() && is_strict_utf8(*output);
}

bool split_relative(const std::string& path, std::vector<std::string>* segments, bool allow_dot = false) {
    if (segments == nullptr || path.empty() || path.size() > kMaxPathBytes || path.front() == '/') return false;
    if (allow_dot && path == ".") {
        segments->clear();
        return true;
    }
    std::size_t start = 0;
    while (start <= path.size()) {
        const std::size_t end = path.find('/', start);
        const std::string segment = path.substr(start, end == std::string::npos ? path.size() - start : end - start);
        if (segment.empty() || segment == "." || segment == "..") return false;
        segments->push_back(segment);
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return !segments->empty();
}

bool split_absolute(const std::string& path, std::vector<std::string>* segments) {
    if (segments == nullptr || path.empty() || path.size() > kMaxPathBytes || path.front() != '/') return false;
    if (path.size() > 1 && path.back() == '/') return false;
    if (path == "/") {
        segments->clear();
        return true;
    }
    return split_relative(path.substr(1), segments);
}

bool valid_model_segment(const std::string& segment) {
    if (segment.empty() || segment.size() > 128 || segment.front() == '.') return false;
    return std::all_of(segment.begin(), segment.end(), [](unsigned char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
            c == '.' || c == '_' || c == '-';
    });
}

int duplicate_descriptor(int descriptor) {
#if defined(F_DUPFD_CLOEXEC)
    return fcntl(descriptor, F_DUPFD_CLOEXEC, 0);
#else
    return dup(descriptor);
#endif
}

#if defined(__ANDROID__) || defined(ARTIFACT_FS_ANDROID_DIRECT_OPEN_TEST)
int open_trusted_app_directory(const std::string& path, bool create) {
    std::vector<std::string> segments;
    if (!split_absolute(path, &segments) || segments.empty()) return -1;
    constexpr int flags = O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC;
    int descriptor = open(path.c_str(), flags);
    if (descriptor < 0 && create && errno == ENOENT) {
        if (mkdir(path.c_str(), 0700) != 0 && errno != EEXIST) return -1;
        descriptor = open(path.c_str(), flags);
    }
    if (descriptor < 0) return -1;
    struct stat metadata {};
    if (fstat(descriptor, &metadata) != 0 || !S_ISDIR(metadata.st_mode)) {
        close(descriptor);
        return -1;
    }
    return descriptor;
}
#endif

int open_absolute_directory(const std::string& path, bool create = false) {
#if defined(__ANDROID__) || defined(ARTIFACT_FS_ANDROID_DIRECT_OPEN_TEST)
    // Android SELinux permits access to the app-owned directory but denies opening
    // the filesystem root for directory reads. The trusted app root is still pinned
    // with O_NOFOLLOW; all model paths below it continue to use descriptor-relative walks.
    return open_trusted_app_directory(path, create);
#else
    std::vector<std::string> segments;
    if (!split_absolute(path, &segments)) return -1;
    int current = open("/", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (current < 0) return -1;
    for (const std::string& segment : segments) {
        int next = openat(current, segment.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (next < 0 && create && errno == ENOENT) {
            if (mkdirat(current, segment.c_str(), 0700) != 0 || fsync(current) != 0) {
                close(current);
                return -1;
            }
            next = openat(current, segment.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        }
        close(current);
        if (next < 0) return -1;
        current = next;
    }
    return current;
#endif
}

int walk_directory(int root, const std::vector<std::string>& segments, bool create) {
    int current = duplicate_descriptor(root);
    if (current < 0) return -1;
    for (const std::string& segment : segments) {
        int next = openat(current, segment.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (next < 0 && create && errno == ENOENT) {
            if (mkdirat(current, segment.c_str(), 0700) != 0 || fsync(current) != 0) {
                close(current);
                return -1;
            }
            next = openat(current, segment.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        }
        close(current);
        if (next < 0) return -1;
        current = next;
    }
    return current;
}

bool revalidate(const RootHandle* root) {
    if (root == nullptr || root->descriptor < 0) return false;
    int models = open_absolute_directory(root->models_root);
    if (models < 0) return false;
    const int current = walk_directory(models, root->model_segments, false);
    close(models);
    if (current < 0) return false;
    struct stat metadata {};
    const bool matches = fstat(current, &metadata) == 0 && S_ISDIR(metadata.st_mode) &&
        metadata.st_dev == root->device && metadata.st_ino == root->inode;
    close(current);
    return matches;
}

bool open_parent(const RootHandle* root, const std::string& relative, int* parent, std::string* name) {
    if (!revalidate(root) || parent == nullptr || name == nullptr) return false;
    std::vector<std::string> segments;
    if (!split_relative(relative, &segments)) return false;
    *name = segments.back();
    segments.pop_back();
    *parent = walk_directory(root->descriptor, segments, false);
    return *parent >= 0;
}

int open_file(const RootHandle* root, const std::string& relative, int flags, mode_t mode = 0) {
    int parent = -1;
    std::string name;
    if (!open_parent(root, relative, &parent, &name)) return -1;
    const int safe_flags = flags | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC;
    const int descriptor = (flags & O_CREAT) != 0
        ? openat(parent, name.c_str(), safe_flags, mode)
        : openat(parent, name.c_str(), safe_flags);
    close(parent);
    if (descriptor < 0) return -1;
    struct stat metadata {};
    if (fstat(descriptor, &metadata) != 0 || !S_ISREG(metadata.st_mode)) {
        close(descriptor);
        return -1;
    }
    return descriptor;
}

RootHandle* as_root(jlong value) {
    return reinterpret_cast<RootHandle*>(static_cast<intptr_t>(value));
}

int as_descriptor(jlong value) {
    return static_cast<int>(value);
}

bool sync_file_descriptor(int descriptor) {
#if defined(__APPLE__) && defined(F_FULLFSYNC)
    return fcntl(descriptor, F_FULLFSYNC) == 0;
#else
    return fsync(descriptor) == 0;
#endif
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_debanshu777_huggingfacemanager_download_NativeArtifactFs_openRoot(
    JNIEnv* env, jobject, jbyteArray models_root_value, jbyteArray model_id_value, jboolean create) {
    std::string models_root;
    std::string model_id;
    std::vector<std::string> model_segments;
    if (!from_java(env, models_root_value, &models_root) || !from_java(env, model_id_value, &model_id) ||
        !split_relative(model_id, &model_segments) || model_segments.size() != 2 ||
        !valid_model_segment(model_segments[0]) || !valid_model_segment(model_segments[1])) return 0;
    const int models = open_absolute_directory(models_root, create == JNI_TRUE);
    if (models < 0) return 0;
    const int descriptor = walk_directory(models, model_segments, create == JNI_TRUE);
    close(models);
    if (descriptor < 0) return 0;
    struct stat metadata {};
    if (fstat(descriptor, &metadata) != 0 || !S_ISDIR(metadata.st_mode)) {
        close(descriptor);
        return 0;
    }
    std::unique_ptr<RootHandle> root(new RootHandle());
    root->descriptor = descriptor;
    root->models_root = models_root;
    root->model_segments = std::move(model_segments);
    root->device = metadata.st_dev;
    root->inode = metadata.st_ino;
    if (!revalidate(root.get())) return 0;
    return static_cast<jlong>(reinterpret_cast<intptr_t>(root.release()));
}

extern "C" JNIEXPORT void JNICALL
Java_com_debanshu777_huggingfacemanager_download_NativeArtifactFs_closeRoot(JNIEnv*, jobject, jlong value) {
    delete as_root(value);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_debanshu777_huggingfacemanager_download_NativeArtifactFs_revalidate(JNIEnv*, jobject, jlong value) {
    return revalidate(as_root(value)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_debanshu777_huggingfacemanager_download_NativeArtifactFs_createParents(
    JNIEnv* env, jobject, jlong value, jbyteArray relative_value) {
    RootHandle* root = as_root(value);
    std::string relative;
    std::vector<std::string> segments;
    if (!from_java(env, relative_value, &relative) || !split_relative(relative, &segments) || !revalidate(root)) return JNI_FALSE;
    segments.pop_back();
    const int parent = walk_directory(root->descriptor, segments, true);
    if (parent < 0) return JNI_FALSE;
    close(parent);
    return revalidate(root) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_debanshu777_huggingfacemanager_download_NativeArtifactFs_openFile(
    JNIEnv* env, jobject, jlong value, jbyteArray relative_value, jint mode) {
    RootHandle* root = as_root(value);
    std::string relative;
    if (!from_java(env, relative_value, &relative)) return -1;
    int flags = O_RDONLY;
    if (mode == kCreateNew) flags = O_WRONLY | O_CREAT | O_EXCL;
    else if (mode == kCreateTruncate) flags = O_WRONLY | O_CREAT | O_TRUNC;
    else if (mode == kAppendExisting) flags = O_WRONLY | O_APPEND;
    else if (mode != kReadOnly) return -1;
    return static_cast<jlong>(open_file(root, relative, flags, 0600));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_debanshu777_huggingfacemanager_download_NativeArtifactFs_read(
    JNIEnv* env, jobject, jlong descriptor_value, jbyteArray output, jint offset, jint count) {
    if (output == nullptr || offset < 0 || count < 0 || count > 64 * 1024 ||
        offset > env->GetArrayLength(output) - count) return -1;
    std::vector<jbyte> bytes(static_cast<std::size_t>(count));
    const ssize_t result = read(as_descriptor(descriptor_value), bytes.data(), static_cast<std::size_t>(count));
    if (result > 0) env->SetByteArrayRegion(output, offset, static_cast<jsize>(result), bytes.data());
    return result < 0 ? -1 : static_cast<jint>(result);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_debanshu777_huggingfacemanager_download_NativeArtifactFs_write(
    JNIEnv* env, jobject, jlong descriptor_value, jbyteArray input, jint offset, jint count) {
    if (input == nullptr || offset < 0 || count < 0 || count > 64 * 1024 ||
        offset > env->GetArrayLength(input) - count) return -1;
    std::vector<jbyte> bytes(static_cast<std::size_t>(count));
    env->GetByteArrayRegion(input, offset, count, bytes.data());
    if (env->ExceptionCheck()) return -1;
    const ssize_t result = write(as_descriptor(descriptor_value), bytes.data(), static_cast<std::size_t>(count));
    return result < 0 ? -1 : static_cast<jint>(result);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_debanshu777_huggingfacemanager_download_NativeArtifactFs_closeFile(
    JNIEnv*, jobject, jlong descriptor_value) {
    return close(as_descriptor(descriptor_value)) == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_debanshu777_huggingfacemanager_download_NativeArtifactFs_size(
    JNIEnv* env, jobject, jlong value, jbyteArray relative_value) {
    std::string relative;
    if (!from_java(env, relative_value, &relative)) return -1;
    const int descriptor = open_file(as_root(value), relative, O_RDONLY);
    if (descriptor < 0) return -1;
    struct stat metadata {};
    const bool valid = fstat(descriptor, &metadata) == 0 && S_ISREG(metadata.st_mode);
    close(descriptor);
    return valid && revalidate(as_root(value)) ? static_cast<jlong>(metadata.st_size) : -1;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_debanshu777_huggingfacemanager_download_NativeArtifactFs_move(
    JNIEnv* env, jobject, jlong value, jbyteArray source_value, jbyteArray target_value) {
    RootHandle* root = as_root(value);
    std::string source;
    std::string target;
    int source_parent = -1;
    int target_parent = -1;
    std::string source_name;
    std::string target_name;
    if (!from_java(env, source_value, &source) || !from_java(env, target_value, &target) ||
        !open_parent(root, source, &source_parent, &source_name) ||
        !open_parent(root, target, &target_parent, &target_name)) {
        if (source_parent >= 0) close(source_parent);
        return JNI_FALSE;
    }
    const bool result = renameat(source_parent, source_name.c_str(), target_parent, target_name.c_str()) == 0;
    close(source_parent);
    close(target_parent);
    return result && revalidate(root) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_debanshu777_huggingfacemanager_download_NativeArtifactFs_delete(
    JNIEnv* env, jobject, jlong value, jbyteArray relative_value) {
    RootHandle* root = as_root(value);
    std::string relative;
    int parent = -1;
    std::string name;
    if (!from_java(env, relative_value, &relative) || !open_parent(root, relative, &parent, &name)) return JNI_FALSE;
    const bool result = unlinkat(parent, name.c_str(), 0) == 0 || errno == ENOENT;
    close(parent);
    return result && revalidate(root) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_debanshu777_huggingfacemanager_download_NativeArtifactFs_syncFile(
    JNIEnv* env, jobject, jlong value, jbyteArray relative_value) {
    RootHandle* root = as_root(value);
    std::string relative;
    if (!from_java(env, relative_value, &relative)) return JNI_FALSE;
    const int descriptor = open_file(root, relative, O_RDONLY);
    if (descriptor < 0) return JNI_FALSE;
    const bool result = sync_file_descriptor(descriptor);
    close(descriptor);
    return result && revalidate(root) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_debanshu777_huggingfacemanager_download_NativeArtifactFs_syncDirectory(
    JNIEnv* env, jobject, jlong value, jbyteArray relative_value) {
    RootHandle* root = as_root(value);
    std::string relative;
    std::vector<std::string> segments;
    if (!from_java(env, relative_value, &relative) || !split_relative(relative, &segments, true) || !revalidate(root)) {
        return JNI_FALSE;
    }
    const int descriptor = walk_directory(root->descriptor, segments, false);
    if (descriptor < 0) return JNI_FALSE;
    const bool result = fsync(descriptor) == 0;
    close(descriptor);
    return result && revalidate(root) ? JNI_TRUE : JNI_FALSE;
}
