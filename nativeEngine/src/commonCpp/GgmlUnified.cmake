# Unified GGML build: llama.cpp first (creates ggml target), then sd.cpp reuses it.
include_guard(GLOBAL)

get_filename_component(_NE_COMMON "${CMAKE_CURRENT_LIST_DIR}" ABSOLUTE)
get_filename_component(REPO_ROOT "${_NE_COMMON}/../../.." ABSOLUTE)

set(LLAMA_SRC "${REPO_ROOT}/libraries/llama.cpp")
set(SD_SRC    "${REPO_ROOT}/libraries/stable-diffusion.cpp")

# Fix GGML_MAX_NAME compatibility between llama.cpp and stable-diffusion.cpp
# stable-diffusion.cpp requires GGML_MAX_NAME >= 128, but llama.cpp defaults to 64
add_compile_definitions(GGML_MAX_NAME=128)

# --- llama.cpp (brings in ggml + llama + common targets) ---
if(NOT EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_SRC}. Run 'git submodule update --init --recursive'")
endif()

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_SRC}" "${CMAKE_BINARY_DIR}/llama-build")

# --- stable-diffusion.cpp (reuses ggml target from llama.cpp) ---
if(NOT EXISTS "${SD_SRC}/CMakeLists.txt")
    message(FATAL_ERROR "stable-diffusion.cpp not found at ${SD_SRC}. Run 'git submodule update --init --recursive'")
endif()

set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_GGML_LIB OFF CACHE BOOL "" FORCE)
set(SD_USE_SYSTEM_GGML ON CACHE BOOL "" FORCE)
set(SD_CUDA OFF CACHE BOOL "" FORCE)
set(SD_OPENCL OFF CACHE BOOL "" FORCE)
set(SD_SYCL OFF CACHE BOOL "" FORCE)
set(SD_MUSA OFF CACHE BOOL "" FORCE)

# Metal/Vulkan are inherited from the parent CMakeLists.txt (platform-specific)
# SD_METAL and SD_VULKAN are set per-platform below in nativeEngine CMakeLists.txt files

add_subdirectory("${SD_SRC}" "${CMAKE_BINARY_DIR}/sd-build")