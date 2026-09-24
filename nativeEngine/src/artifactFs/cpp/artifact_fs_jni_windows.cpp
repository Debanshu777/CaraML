#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <winternl.h>
#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace {

constexpr std::size_t kMaxPathChars = 4096;
constexpr jint kReadOnly = 0;
constexpr jint kCreateNew = 1;
constexpr jint kCreateTruncate = 2;
constexpr jint kAppendExisting = 3;

using NtCreateFileFunction = NTSTATUS(NTAPI*)(
    PHANDLE, ACCESS_MASK, POBJECT_ATTRIBUTES, PIO_STATUS_BLOCK, PLARGE_INTEGER,
    ULONG, ULONG, ULONG, ULONG, PVOID, ULONG);
using NtFlushBuffersFileFunction = NTSTATUS(NTAPI*)(HANDLE, PIO_STATUS_BLOCK);

NtCreateFileFunction nt_create_file() {
    static const auto function = reinterpret_cast<NtCreateFileFunction>(
        GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "NtCreateFile"));
    return function;
}

NtFlushBuffersFileFunction nt_flush_buffers_file() {
    static const auto function = reinterpret_cast<NtFlushBuffersFileFunction>(
        GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "NtFlushBuffersFile"));
    return function;
}

bool success(NTSTATUS status) { return status >= 0; }

struct FileIdentity {
    DWORD volume = 0;
    DWORD high = 0;
    DWORD low = 0;

    bool operator==(const FileIdentity& other) const {
        return volume == other.volume && high == other.high && low == other.low;
    }
};

struct RootHandle {
    HANDLE handle = INVALID_HANDLE_VALUE;
    std::wstring models_root;
    std::vector<std::wstring> model_segments;
    FileIdentity identity;
    ~RootHandle() { if (handle != INVALID_HANDLE_VALUE) CloseHandle(handle); }
};

bool from_java(JNIEnv* env, jbyteArray input, std::wstring* output) {
    if (input == nullptr || output == nullptr) return false;
    const jsize length = env->GetArrayLength(input);
    if (length <= 0 || static_cast<std::size_t>(length) > kMaxPathChars) return false;
    std::string bytes(static_cast<std::size_t>(length), '\0');
    env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    if (env->ExceptionCheck() || bytes.find('\0') != std::string::npos) return false;
    const int wide_length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, bytes.data(), length, nullptr, 0);
    if (wide_length <= 0 || static_cast<std::size_t>(wide_length) > kMaxPathChars) return false;
    output->resize(static_cast<std::size_t>(wide_length));
    return MultiByteToWideChar(
        CP_UTF8,
        MB_ERR_INVALID_CHARS,
        bytes.data(),
        length,
        output->data(),
        wide_length
    ) == wide_length;
}

bool split_relative(
    const std::wstring& input,
    std::vector<std::wstring>* output,
    bool allow_dot = false) {
    if (output == nullptr || input.empty() || input.size() > kMaxPathChars ||
        input.front() == L'/' || input.front() == L'\\') return false;
    if (allow_dot && input == L".") { output->clear(); return true; }
    std::size_t start = 0;
    while (start <= input.size()) {
        const std::size_t end = input.find_first_of(L"/\\", start);
        const std::wstring segment = input.substr(
            start, end == std::wstring::npos ? input.size() - start : end - start);
        if (segment.empty() || segment == L"." || segment == L".." ||
            segment.find(L':') != std::wstring::npos) return false;
        output->push_back(segment);
        if (end == std::wstring::npos) break;
        start = end + 1;
    }
    return !output->empty();
}

bool valid_model_segment(const std::wstring& segment) {
    if (segment.empty() || segment.size() > 128 || segment.front() == L'.') return false;
    return std::all_of(segment.begin(), segment.end(), [](wchar_t c) {
        return (c >= L'a' && c <= L'z') || (c >= L'A' && c <= L'Z') ||
            (c >= L'0' && c <= L'9') || c == L'.' || c == L'_' || c == L'-';
    });
}

bool identity_of(HANDLE handle, FileIdentity* identity) {
    BY_HANDLE_FILE_INFORMATION information {};
    if (identity == nullptr || !GetFileInformationByHandle(handle, &information)) return false;
    identity->volume = information.dwVolumeSerialNumber;
    identity->high = information.nFileIndexHigh;
    identity->low = information.nFileIndexLow;
    return true;
}

bool rejects_reparse(HANDLE handle, bool require_directory) {
    FILE_ATTRIBUTE_TAG_INFO tag {};
    if (!GetFileInformationByHandleEx(handle, FileAttributeTagInfo, &tag, sizeof(tag))) return false;
    if ((tag.FileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) return false;
    const bool directory = (tag.FileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
    return require_directory ? directory : !directory && GetFileType(handle) == FILE_TYPE_DISK;
}

HANDLE open_child(
    HANDLE parent,
    const std::wstring& name,
    ACCESS_MASK access,
    ULONG disposition,
    bool directory) {
    auto create_file = nt_create_file();
    if (create_file == nullptr || parent == INVALID_HANDLE_VALUE || name.empty() || name.size() > 255) {
        return INVALID_HANDLE_VALUE;
    }
    UNICODE_STRING unicode {};
    unicode.Buffer = const_cast<PWSTR>(name.data());
    unicode.Length = static_cast<USHORT>(name.size() * sizeof(wchar_t));
    unicode.MaximumLength = unicode.Length;
    OBJECT_ATTRIBUTES attributes {};
    InitializeObjectAttributes(&attributes, &unicode, OBJ_CASE_INSENSITIVE, parent, nullptr);
    IO_STATUS_BLOCK status_block {};
    HANDLE result = INVALID_HANDLE_VALUE;
    const ULONG options = FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT |
        (directory ? FILE_DIRECTORY_FILE : FILE_NON_DIRECTORY_FILE);
    const NTSTATUS status = create_file(
        &result,
        access | SYNCHRONIZE,
        &attributes,
        &status_block,
        nullptr,
        directory ? FILE_ATTRIBUTE_DIRECTORY : FILE_ATTRIBUTE_NORMAL,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        disposition,
        options,
        nullptr,
        0);
    if (!success(status) || !rejects_reparse(result, directory)) {
        if (result != INVALID_HANDLE_VALUE) CloseHandle(result);
        return INVALID_HANDLE_VALUE;
    }
    return result;
}

bool flush_handle(HANDLE handle) {
    auto flush = nt_flush_buffers_file();
    if (flush == nullptr) return false;
    IO_STATUS_BLOCK status {};
    return success(flush(handle, &status));
}

bool flush_directory_handle(HANDLE handle) {
    HANDLE writable = ReOpenFile(
        handle,
        GENERIC_READ | GENERIC_WRITE,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT);
    if (writable == INVALID_HANDLE_VALUE || !rejects_reparse(writable, true)) {
        if (writable != INVALID_HANDLE_VALUE) CloseHandle(writable);
        return false;
    }
    const bool result = flush_handle(writable);
    CloseHandle(writable);
    return result;
}

HANDLE open_absolute_directory(const std::wstring& input, bool create = false) {
    if (input.size() < 3 || input.size() > kMaxPathChars || input[1] != L':' ||
        (input[2] != L'/' && input[2] != L'\\')) return INVALID_HANDLE_VALUE;
    std::wstring root = L"\\\\?\\" + input.substr(0, 2) + L"\\";
    HANDLE current = CreateFileW(
        root.c_str(), FILE_READ_ATTRIBUTES | FILE_TRAVERSE | SYNCHRONIZE,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING,
        FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, nullptr);
    if (current == INVALID_HANDLE_VALUE || !rejects_reparse(current, true)) {
        if (current != INVALID_HANDLE_VALUE) CloseHandle(current);
        return INVALID_HANDLE_VALUE;
    }
    std::wstring remainder = input.substr(3);
    std::vector<std::wstring> segments;
    if (!remainder.empty() && !split_relative(remainder, &segments)) {
        CloseHandle(current);
        return INVALID_HANDLE_VALUE;
    }
    for (const auto& segment : segments) {
        HANDLE next = open_child(current, segment, FILE_READ_ATTRIBUTES | FILE_TRAVERSE, FILE_OPEN, true);
        if (next == INVALID_HANDLE_VALUE && create) {
            next = open_child(current, segment, FILE_READ_ATTRIBUTES | FILE_TRAVERSE, FILE_CREATE, true);
            if (next != INVALID_HANDLE_VALUE) flush_directory_handle(current);
        }
        CloseHandle(current);
        if (next == INVALID_HANDLE_VALUE) return INVALID_HANDLE_VALUE;
        current = next;
    }
    return current;
}

HANDLE walk(HANDLE start, const std::vector<std::wstring>& segments, bool create) {
    HANDLE current = INVALID_HANDLE_VALUE;
    if (!DuplicateHandle(GetCurrentProcess(), start, GetCurrentProcess(), &current, 0, FALSE, DUPLICATE_SAME_ACCESS)) {
        return INVALID_HANDLE_VALUE;
    }
    for (const auto& segment : segments) {
        bool created = false;
        HANDLE next = open_child(current, segment, FILE_READ_ATTRIBUTES | FILE_TRAVERSE, FILE_OPEN, true);
        if (next == INVALID_HANDLE_VALUE && create) {
            next = open_child(current, segment, FILE_READ_ATTRIBUTES | FILE_TRAVERSE, FILE_OPEN_IF, true);
            created = next != INVALID_HANDLE_VALUE;
        }
        if (next == INVALID_HANDLE_VALUE) {
            if (next != INVALID_HANDLE_VALUE) CloseHandle(next);
            CloseHandle(current);
            return INVALID_HANDLE_VALUE;
        }
        if (created) flush_directory_handle(current);
        CloseHandle(current);
        current = next;
    }
    return current;
}

bool revalidate(const RootHandle* root) {
    if (root == nullptr || root->handle == INVALID_HANDLE_VALUE) return false;
    HANDLE models = open_absolute_directory(root->models_root);
    if (models == INVALID_HANDLE_VALUE) return false;
    HANDLE current = walk(models, root->model_segments, false);
    CloseHandle(models);
    if (current == INVALID_HANDLE_VALUE) return false;
    FileIdentity identity;
    const bool matches = identity_of(current, &identity) && identity == root->identity;
    CloseHandle(current);
    return matches;
}

bool open_parent(
    RootHandle* root,
    const std::wstring& path,
    HANDLE* parent,
    std::wstring* name) {
    if (!revalidate(root) || parent == nullptr || name == nullptr) return false;
    std::vector<std::wstring> segments;
    if (!split_relative(path, &segments)) return false;
    *name = segments.back();
    segments.pop_back();
    *parent = walk(root->handle, segments, false);
    return *parent != INVALID_HANDLE_VALUE;
}

HANDLE open_file(RootHandle* root, const std::wstring& path, ACCESS_MASK access, ULONG disposition) {
    HANDLE parent = INVALID_HANDLE_VALUE;
    std::wstring name;
    if (!open_parent(root, path, &parent, &name)) return INVALID_HANDLE_VALUE;
    HANDLE result = open_child(parent, name, access, disposition, false);
    CloseHandle(parent);
    return result;
}

RootHandle* as_root(jlong value) {
    return reinterpret_cast<RootHandle*>(static_cast<intptr_t>(value));
}

HANDLE as_file(jlong value) { return reinterpret_cast<HANDLE>(static_cast<intptr_t>(value)); }

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_debanshu777_huggingfacemanager_download_NativeArtifactFs_openRoot(
    JNIEnv* env, jobject, jbyteArray models_value, jbyteArray model_value, jboolean create) {
    std::wstring models;
    std::wstring model;
    std::vector<std::wstring> segments;
    if (!from_java(env, models_value, &models) || !from_java(env, model_value, &model) ||
        !split_relative(model, &segments) || segments.size() != 2 ||
        !valid_model_segment(segments[0]) || !valid_model_segment(segments[1])) return 0;
    HANDLE models_handle = open_absolute_directory(models, create == JNI_TRUE);
    if (models_handle == INVALID_HANDLE_VALUE) return 0;
    HANDLE root_handle = walk(models_handle, segments, create == JNI_TRUE);
    CloseHandle(models_handle);
    if (root_handle == INVALID_HANDLE_VALUE) return 0;
    std::unique_ptr<RootHandle> root(new RootHandle());
    root->handle = root_handle;
    root->models_root = models;
    root->model_segments = std::move(segments);
    if (!identity_of(root_handle, &root->identity) || !revalidate(root.get())) return 0;
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
    JNIEnv* env, jobject, jlong value, jbyteArray path_value) {
    RootHandle* root = as_root(value);
    std::wstring path;
    std::vector<std::wstring> segments;
    if (!from_java(env, path_value, &path) || !split_relative(path, &segments) || !revalidate(root)) return JNI_FALSE;
    segments.pop_back();
    HANDLE parent = walk(root->handle, segments, true);
    if (parent == INVALID_HANDLE_VALUE) return JNI_FALSE;
    CloseHandle(parent);
    return revalidate(root) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_debanshu777_huggingfacemanager_download_NativeArtifactFs_openFile(
    JNIEnv* env, jobject, jlong value, jbyteArray path_value, jint mode) {
    std::wstring path;
    if (!from_java(env, path_value, &path)) return -1;
    ACCESS_MASK access = FILE_GENERIC_READ;
    ULONG disposition = FILE_OPEN;
    if (mode == kCreateNew) { access = FILE_GENERIC_WRITE; disposition = FILE_CREATE; }
    else if (mode == kCreateTruncate) { access = FILE_GENERIC_WRITE; disposition = FILE_OVERWRITE_IF; }
    else if (mode == kAppendExisting) { access = FILE_APPEND_DATA; disposition = FILE_OPEN; }
    else if (mode != kReadOnly) return -1;
    HANDLE file = open_file(as_root(value), path, access, disposition);
    return file == INVALID_HANDLE_VALUE ? -1 : static_cast<jlong>(reinterpret_cast<intptr_t>(file));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_debanshu777_huggingfacemanager_download_NativeArtifactFs_read(
    JNIEnv* env, jobject, jlong value, jbyteArray output, jint offset, jint count) {
    if (output == nullptr || offset < 0 || count < 0 || count > 64 * 1024 ||
        offset > env->GetArrayLength(output) - count) return -1;
    std::vector<jbyte> bytes(static_cast<std::size_t>(count));
    DWORD read_count = 0;
    if (!ReadFile(as_file(value), bytes.data(), static_cast<DWORD>(count), &read_count, nullptr)) return -1;
    if (read_count > 0) env->SetByteArrayRegion(output, offset, static_cast<jsize>(read_count), bytes.data());
    return static_cast<jint>(read_count);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_debanshu777_huggingfacemanager_download_NativeArtifactFs_write(
    JNIEnv* env, jobject, jlong value, jbyteArray input, jint offset, jint count) {
    if (input == nullptr || offset < 0 || count < 0 || count > 64 * 1024 ||
        offset > env->GetArrayLength(input) - count) return -1;
    std::vector<jbyte> bytes(static_cast<std::size_t>(count));
    env->GetByteArrayRegion(input, offset, count, bytes.data());
    if (env->ExceptionCheck()) return -1;
    DWORD written = 0;
    return WriteFile(as_file(value), bytes.data(), static_cast<DWORD>(count), &written, nullptr)
        ? static_cast<jint>(written) : -1;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_debanshu777_huggingfacemanager_download_NativeArtifactFs_closeFile(JNIEnv*, jobject, jlong value) {
    return CloseHandle(as_file(value)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_debanshu777_huggingfacemanager_download_NativeArtifactFs_size(
    JNIEnv* env, jobject, jlong value, jbyteArray path_value) {
    RootHandle* root = as_root(value);
    std::wstring path;
    if (!from_java(env, path_value, &path)) return -1;
    HANDLE file = open_file(root, path, FILE_GENERIC_READ, FILE_OPEN);
    LARGE_INTEGER size {};
    const bool result = file != INVALID_HANDLE_VALUE && GetFileSizeEx(file, &size);
    if (file != INVALID_HANDLE_VALUE) CloseHandle(file);
    return result && revalidate(root) ? static_cast<jlong>(size.QuadPart) : -1;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_debanshu777_huggingfacemanager_download_NativeArtifactFs_move(
    JNIEnv* env, jobject, jlong value, jbyteArray source_value, jbyteArray target_value) {
    RootHandle* root = as_root(value);
    std::wstring source;
    std::wstring target;
    HANDLE source_parent = INVALID_HANDLE_VALUE;
    HANDLE target_parent = INVALID_HANDLE_VALUE;
    std::wstring source_name;
    std::wstring target_name;
    if (!from_java(env, source_value, &source) || !from_java(env, target_value, &target) ||
        !open_parent(root, source, &source_parent, &source_name) ||
        !open_parent(root, target, &target_parent, &target_name)) {
        if (source_parent != INVALID_HANDLE_VALUE) CloseHandle(source_parent);
        return JNI_FALSE;
    }
    HANDLE source_file = open_child(source_parent, source_name, DELETE, FILE_OPEN, false);
    const std::size_t bytes = target_name.size() * sizeof(wchar_t);
    std::vector<unsigned char> storage(sizeof(FILE_RENAME_INFO) + bytes);
    auto* info = reinterpret_cast<FILE_RENAME_INFO*>(storage.data());
    info->ReplaceIfExists = TRUE;
    info->RootDirectory = target_parent;
    info->FileNameLength = static_cast<DWORD>(bytes);
    std::copy(target_name.begin(), target_name.end(), info->FileName);
    const bool result = source_file != INVALID_HANDLE_VALUE &&
        SetFileInformationByHandle(source_file, FileRenameInfo, info, static_cast<DWORD>(storage.size()));
    if (source_file != INVALID_HANDLE_VALUE) CloseHandle(source_file);
    CloseHandle(source_parent);
    CloseHandle(target_parent);
    return result && revalidate(root) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_debanshu777_huggingfacemanager_download_NativeArtifactFs_delete(
    JNIEnv* env, jobject, jlong value, jbyteArray path_value) {
    RootHandle* root = as_root(value);
    std::wstring path;
    if (!from_java(env, path_value, &path)) return JNI_FALSE;
    HANDLE file = open_file(root, path, DELETE, FILE_OPEN);
    if (file == INVALID_HANDLE_VALUE) return GetLastError() == ERROR_FILE_NOT_FOUND ? JNI_TRUE : JNI_FALSE;
    FILE_DISPOSITION_INFO disposition { TRUE };
    const bool result = SetFileInformationByHandle(file, FileDispositionInfo, &disposition, sizeof(disposition));
    CloseHandle(file);
    return result && revalidate(root) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_debanshu777_huggingfacemanager_download_NativeArtifactFs_syncFile(
    JNIEnv* env, jobject, jlong value, jbyteArray path_value) {
    RootHandle* root = as_root(value);
    std::wstring path;
    if (!from_java(env, path_value, &path)) return JNI_FALSE;
    HANDLE file = open_file(root, path, FILE_GENERIC_READ | FILE_GENERIC_WRITE, FILE_OPEN);
    const bool result = file != INVALID_HANDLE_VALUE && flush_handle(file);
    if (file != INVALID_HANDLE_VALUE) CloseHandle(file);
    return result && revalidate(root) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_debanshu777_huggingfacemanager_download_NativeArtifactFs_syncDirectory(
    JNIEnv* env, jobject, jlong value, jbyteArray path_value) {
    RootHandle* root = as_root(value);
    std::wstring path;
    std::vector<std::wstring> segments;
    if (!from_java(env, path_value, &path) || !split_relative(path, &segments, true) || !revalidate(root)) {
        return JNI_FALSE;
    }
    HANDLE directory = walk(root->handle, segments, false);
    // Windows does not guarantee directory-handle flushing on every supported
    // filesystem. Keep the pinned/reparse-safe open as the acceptance boundary
    // and request a flush when the volume supports it.
    const bool result = directory != INVALID_HANDLE_VALUE;
    if (directory != INVALID_HANDLE_VALUE) flush_directory_handle(directory);
    if (directory != INVALID_HANDLE_VALUE) CloseHandle(directory);
    return result && revalidate(root) ? JNI_TRUE : JNI_FALSE;
}
