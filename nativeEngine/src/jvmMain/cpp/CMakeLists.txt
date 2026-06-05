cmake_minimum_required(VERSION 3.22.1)
project(llama_runner_desktop VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Stable Diffusion backend configuration
if(APPLE)
    set(SD_METAL ON CACHE BOOL "" FORCE)
    set(SD_VULKAN OFF CACHE BOOL "" FORCE)
else()
    set(SD_METAL OFF CACHE BOOL "" FORCE)
    # Vulkan GPU acceleration for diffusion on Linux/Windows.
    # Auto-detects Vulkan SDK; falls back to CPU-only if not found.
    set(SD_VULKAN OFF CACHE BOOL "" FORCE)
    foreach(_vk_prefix "$ENV{VULKAN_SDK}" "$ENV{VK_SDK_PATH}" "/usr" "/usr/local")
        if(_vk_prefix AND EXISTS "${_vk_prefix}/include/vulkan/vulkan.h")
            set(SD_VULKAN ON CACHE BOOL "" FORCE)
            set(GGML_VULKAN ON CACHE BOOL "" FORCE)
            message(STATUS "Vulkan found at ${_vk_prefix}/include — enabling SD_VULKAN for desktop diffusion")
            break()
        endif()
    endforeach()
    if(NOT SD_VULKAN)
        message(STATUS "Vulkan SDK not found — desktop diffusion will run on CPU only")
    endif()
endif()

include("${CMAKE_CURRENT_LIST_DIR}/../../commonCpp/GgmlUnified.cmake")
include("${CMAKE_CURRENT_LIST_DIR}/../../../../runner/src/commonCpp/LlamaRunnerCommon.cmake")

set(JNI_INCLUDE_DIRS "")
if(DEFINED ENV{JAVA_HOME})
    set(JAVA_HOME "$ENV{JAVA_HOME}")
    set(_jni_include "${JAVA_HOME}/include")
    if(EXISTS "${_jni_include}/jni.h")
        list(APPEND JNI_INCLUDE_DIRS "${_jni_include}")
        if(APPLE AND EXISTS "${_jni_include}/darwin/jni_md.h")
            list(APPEND JNI_INCLUDE_DIRS "${_jni_include}/darwin")
        elseif(UNIX AND NOT APPLE AND EXISTS "${_jni_include}/linux/jni_md.h")
            list(APPEND JNI_INCLUDE_DIRS "${_jni_include}/linux")
        elseif(WIN32 AND EXISTS "${_jni_include}/win32/jni_md.h")
            list(APPEND JNI_INCLUDE_DIRS "${_jni_include}/win32")
        endif()
        message(STATUS "JNI includes from JAVA_HOME: ${JNI_INCLUDE_DIRS}")
    endif()
endif()
if(JNI_INCLUDE_DIRS STREQUAL "")
    message(FATAL_ERROR
        "JNI headers not found. Set JAVA_HOME to your JDK root.\n"
        "Ensure it points to a JDK (not JRE) with include/jni.h"
    )
endif()

add_library(
    llama_runner
    SHARED
    ${LLAMA_RUNNER_CORE_SOURCES}
    ${LLAMA_RUNNER_JNI_SOURCE}
)

target_include_directories(llama_runner PRIVATE
    ${LLAMA_RUNNER_INCLUDE_DIRS}
    ${JNI_INCLUDE_DIRS}
)

target_link_libraries(llama_runner
    PRIVATE
    llama
    llama-common
)

set_target_properties(llama_runner PROPERTIES OUTPUT_NAME "llama_runner")

if(CMAKE_BUILD_TYPE MATCHES Release)
    target_compile_options(llama_runner PRIVATE -O3)
endif()

# --- diffusion_runner (stable-diffusion.cpp wrapper) ---
include("${CMAKE_CURRENT_LIST_DIR}/../../../../diffusionRunner/src/commonCpp/DiffusionRunnerCommon.cmake")

add_library(
    diffusion_runner
    SHARED
    ${DIFFUSION_RUNNER_CORE_SOURCES}
    ${DIFFUSION_RUNNER_JNI_SOURCE}
)

target_include_directories(diffusion_runner PRIVATE
    ${DIFFUSION_RUNNER_INCLUDE_DIRS}
    ${JNI_INCLUDE_DIRS}
)

target_link_libraries(diffusion_runner PRIVATE stable-diffusion)

# ggml-vulkan.h needed for ggml_backend_vk_get_device_count() in diffusion_runner_core.cpp
if(SD_VULKAN)
    target_include_directories(diffusion_runner PRIVATE "${LLAMA_SRC}/ggml/include")
endif()
# Link ggml-vulkan into diffusion_runner when Vulkan is enabled.
if(SD_VULKAN AND TARGET ggml-vulkan)
    target_link_libraries(diffusion_runner PRIVATE ggml-vulkan)
    target_compile_definitions(diffusion_runner PRIVATE SD_USE_VULKAN)
    add_dependencies(diffusion_runner ggml-vulkan)
    message(STATUS "diffusion_runner: linked ggml-vulkan for GPU-accelerated image generation")
endif()
set_target_properties(diffusion_runner PROPERTIES OUTPUT_NAME "diffusion_runner")

if(CMAKE_BUILD_TYPE MATCHES Release)
    target_compile_options(diffusion_runner PRIVATE -O3)
endif()
