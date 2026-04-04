cmake_minimum_required(VERSION 3.22.1)
project(llama_runner VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(DEFINED ANDROID_ABI)
    message(STATUS "Configuring llama.cpp for Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM" CACHE STRING "")
        set(GGML_CPU_KLEIDIAI ON CACHE BOOL "")
        set(GGML_OPENMP ON CACHE BOOL "")
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86" CACHE STRING "")
        set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "")
        set(GGML_OPENMP OFF CACHE BOOL "")
    endif()
endif()

# Stable Diffusion backend configuration
set(SD_METAL OFF CACHE BOOL "" FORCE)
set(SD_VULKAN OFF CACHE BOOL "" FORCE)

include("${CMAKE_CURRENT_LIST_DIR}/../../commonCpp/GgmlUnified.cmake")
include("${CMAKE_CURRENT_LIST_DIR}/../../../../runner/src/commonCpp/LlamaRunnerCommon.cmake")

find_library(log-lib log)

add_library(
    llama_runner
    SHARED
    ${LLAMA_RUNNER_CORE_SOURCES}
    ${LLAMA_RUNNER_JNI_SOURCE}
)

target_include_directories(llama_runner PRIVATE ${LLAMA_RUNNER_INCLUDE_DIRS})

target_link_libraries(llama_runner PRIVATE llama common ${log-lib})

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

target_include_directories(diffusion_runner PRIVATE ${DIFFUSION_RUNNER_INCLUDE_DIRS})
target_link_libraries(diffusion_runner PRIVATE stable-diffusion ${log-lib})

if(CMAKE_BUILD_TYPE MATCHES Release)
    target_compile_options(diffusion_runner PRIVATE -O3)
endif()
