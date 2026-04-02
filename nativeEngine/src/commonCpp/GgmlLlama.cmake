# Phase 1: single GGML via llama.cpp vendored tree. Built once per CMake invocation.
# Phase 2: replace this with UnifiedNativeBuild.cmake (GGML once, then llama + sd).

include_guard(GLOBAL)

get_filename_component(_NE_COMMON "${CMAKE_CURRENT_LIST_DIR}" ABSOLUTE)
get_filename_component(REPO_ROOT "${_NE_COMMON}/../../.." ABSOLUTE)
set(LLAMA_SRC "${REPO_ROOT}/libraries/llama.cpp")

if(NOT EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_SRC}. Run 'git submodule update --init --recursive'")
endif()

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_SRC}" "${CMAKE_BINARY_DIR}/llama-build")
