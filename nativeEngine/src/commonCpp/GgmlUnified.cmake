# Unified GGML build: llama.cpp first (creates ggml target), then sd.cpp reuses it.
include_guard(GLOBAL)

get_filename_component(_NE_COMMON "${CMAKE_CURRENT_LIST_DIR}" ABSOLUTE)
get_filename_component(REPO_ROOT "${_NE_COMMON}/../../.." ABSOLUTE)

if(NOT DEFINED LLAMA_SRC)
    set(LLAMA_SRC "${REPO_ROOT}/libraries/llama.cpp")
endif()
get_filename_component(LLAMA_SRC "${LLAMA_SRC}" ABSOLUTE)
set(SD_SRC    "${REPO_ROOT}/libraries/stable-diffusion.cpp")

# Fix GGML_MAX_NAME compatibility between llama.cpp and stable-diffusion.cpp
# stable-diffusion.cpp requires GGML_MAX_NAME >= 160, but llama.cpp defaults to 64.
# Define the candidate pair's stricter shared bound before either engine target exists.
add_compile_definitions(GGML_MAX_NAME=160)

option(CARAML_ENABLE_LLAMA "Build the llama inference engine" ON)
option(CARAML_ENABLE_DIFFUSION "Build the diffusion inference engine" ON)
if(NOT CARAML_ENABLE_LLAMA AND NOT CARAML_ENABLE_DIFFUSION)
    message(FATAL_ERROR "At least one CaraML native engine must be selected")
endif()

# Fail closed when a caller reuses one CMake cache for a different engine or
# backend selection. Gradle gives opt-in desktop profiles isolated directories.
function(caraml_lock_native_profile)
    set(_profile
        "${CMAKE_SYSTEM_NAME}|${CMAKE_OSX_ARCHITECTURES}|${ANDROID_ABI}|${CARAML_NATIVE_BACKEND}|llama=${CARAML_ENABLE_LLAMA}|diffusion=${CARAML_ENABLE_DIFFUSION}")
    if(DEFINED CARAML_CONFIGURED_NATIVE_PROFILE AND
       NOT "${CARAML_CONFIGURED_NATIVE_PROFILE}" STREQUAL "${_profile}")
        message(FATAL_ERROR
            "This CMake build directory is locked to native profile '${CARAML_CONFIGURED_NATIVE_PROFILE}', "
            "but '${_profile}' was requested. Use a separate build directory or explicitly remove this "
            "directory's CMake cache before reconfiguring; CaraML will not reuse stale engine binaries.")
    endif()
    set(CARAML_CONFIGURED_NATIVE_PROFILE "${_profile}" CACHE INTERNAL "CaraML native engine/backend profile" FORCE)
endfunction()

# GGML comes from the pinned llama.cpp checkout. A diffusion-only build uses
# that GGML source directly and never adds llama/common inference targets.
if(NOT EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_SRC}. Run 'git submodule update --init'")
endif()
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
# Use upstream runtime dispatch; never tune a portable artifact to its build host.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if(CARAML_ENABLE_LLAMA)
    add_subdirectory("${LLAMA_SRC}" "${CMAKE_BINARY_DIR}/llama-build")
else()
    set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    add_subdirectory("${LLAMA_SRC}/ggml" "${CMAKE_BINARY_DIR}/ggml-build")
endif()

if(CARAML_ENABLE_DIFFUSION)
    if(NOT EXISTS "${SD_SRC}/CMakeLists.txt")
        message(FATAL_ERROR "stable-diffusion.cpp not found at ${SD_SRC}. Run 'git submodule update --init'")
    endif()
    set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(SD_BUILD_SHARED_GGML_LIB OFF CACHE BOOL "" FORCE)
    set(SD_USE_SYSTEM_GGML ON CACHE BOOL "" FORCE)
    set(SD_USE_UPSTREAM_GGML ON CACHE BOOL "" FORCE)
    set(SD_GGML_SOURCE_DIR "${LLAMA_SRC}/ggml" CACHE PATH "" FORCE)
    set(SD_WEBP OFF CACHE BOOL "" FORCE)
    set(SD_WEBM OFF CACHE BOOL "" FORCE)
    set(SD_CUDA OFF CACHE BOOL "" FORCE)
    set(SD_OPENCL OFF CACHE BOOL "" FORCE)
    set(SD_SYCL OFF CACHE BOOL "" FORCE)
    set(SD_MUSA OFF CACHE BOOL "" FORCE)
    add_subdirectory("${SD_SRC}" "${CMAKE_BINARY_DIR}/sd-build")

    get_property(_sd_ggml_include TARGET stable-diffusion PROPERTY SD_GGML_PRIVATE_INCLUDE_DIR)
    if(NOT _sd_ggml_include MATCHES "^${LLAMA_SRC}/ggml")
        message(FATAL_ERROR "stable-diffusion.cpp is not using CaraML's pinned llama.cpp GGML tree")
    endif()
    get_target_property(_sd_compile_definitions stable-diffusion COMPILE_DEFINITIONS)
    if(NOT "SD_USE_UPSTREAM_GGML" IN_LIST _sd_compile_definitions)
        message(FATAL_ERROR "stable-diffusion.cpp upstream-GGML compatibility mode is disabled")
    endif()
endif()

# Phase 07: When GGML_BACKEND_DL=ON, ggml-cpu is built as a MODULE (dlopen-only)
# and cannot be linked directly. Remove it from stable-diffusion's link deps.
if(GGML_BACKEND_DL AND TARGET ggml-cpu)
    foreach(_sd_target stable-diffusion sd)
        if(TARGET ${_sd_target})
            get_target_property(_sd_links ${_sd_target} LINK_LIBRARIES)
            if(_sd_links)
                list(REMOVE_ITEM _sd_links ggml-cpu)
                set_target_properties(${_sd_target} PROPERTIES LINK_LIBRARIES "${_sd_links}")
            endif()
        endif()
    endforeach()
endif()
