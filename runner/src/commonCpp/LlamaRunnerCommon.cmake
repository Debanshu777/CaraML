include_guard(GLOBAL)

set(LLAMA_RUNNER_COMMON_DIR "${CMAKE_CURRENT_LIST_DIR}")
set(LLAMA_SRC "${LLAMA_RUNNER_COMMON_DIR}/../../../libraries/llama.cpp")

if(NOT EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_SRC}. Please run 'git submodule update --init --recursive'")
endif()

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_SRC}" "${CMAKE_BINARY_DIR}/llama-build")

set(LLAMA_RUNNER_CORE_SOURCES
    "${LLAMA_RUNNER_COMMON_DIR}/llama_runner_core.cpp"
)

set(LLAMA_RUNNER_JNI_SOURCE
    "${LLAMA_RUNNER_COMMON_DIR}/llama_runner_jni.cpp"
)

set(LLAMA_RUNNER_INCLUDE_DIRS
    "${LLAMA_RUNNER_COMMON_DIR}"
    "${LLAMA_SRC}/include"
    "${LLAMA_SRC}/common"
    "${LLAMA_SRC}/ggml/include"
    "${LLAMA_SRC}/ggml/src"
)
