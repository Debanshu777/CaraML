cmake_minimum_required(VERSION 3.22.1)
project(llama_runner VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(DEFINED ANDROID_ABI)
    message(STATUS "Configuring llama.cpp for Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM" CACHE STRING "" FORCE)
        set(GGML_CPU_KLEIDIAI      ON  CACHE BOOL "" FORCE)
        set(GGML_BACKEND_DL        ON  CACHE BOOL "" FORCE)
        set(GGML_CPU_ALL_VARIANTS  ON  CACHE BOOL "" FORCE)
        set(GGML_OPENMP            ON  CACHE BOOL "" FORCE)

        # Phase 09: Vulkan GPU offload via NDK-bundled glslc (NDK r12+, no host Vulkan SDK needed).
        if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
            set(_NDK_HOST "darwin-x86_64")
        elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
            set(_NDK_HOST "linux-x86_64")
        else()
            set(_NDK_HOST "windows-x86_64")
        endif()
        set(_NDK_GLSLC "${ANDROID_NDK}/shader-tools/${_NDK_HOST}/glslc")
        if(EXISTS "${_NDK_GLSLC}")
            set(Vulkan_GLSLC_EXECUTABLE "${_NDK_GLSLC}" CACHE FILEPATH "" FORCE)
            set(GGML_VULKAN ON CACHE BOOL "" FORCE)
            message(STATUS "Vulkan enabled — NDK glslc: ${_NDK_GLSLC}")

            # vulkan.hpp and spirv/unified1/spirv.hpp: NDK r27+ dropped the C++ Vulkan
            # headers. The NDK 25/26 copies are Vulkan 1.2-era and incompatible with
            # current ggml-vulkan.cpp (requires Vulkan 1.3 API). Use Homebrew headers
            # on macOS; on Linux use the system Vulkan SDK package.
            set(_VULKAN_HPP_FOUND FALSE)
            set(_SPIRV_HPP_FOUND FALSE)

            if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
                # Homebrew: brew install vulkan-headers spirv-headers
                foreach(_brew_prefix "/opt/homebrew" "/usr/local")
                    if(EXISTS "${_brew_prefix}/include/vulkan/vulkan.hpp")
                        include_directories("${_brew_prefix}/include")
                        message(STATUS "Vulkan/SPIRV C++ headers: ${_brew_prefix}/include")
                        set(_VULKAN_HPP_FOUND TRUE)
                        set(_SPIRV_HPP_FOUND TRUE)
                        break()
                    endif()
                endforeach()
            else()
                # Linux: apt install libvulkan-dev spirv-headers
                foreach(_sys_prefix "/usr" "/usr/local")
                    if(EXISTS "${_sys_prefix}/include/vulkan/vulkan.hpp")
                        include_directories("${_sys_prefix}/include")
                        message(STATUS "Vulkan/SPIRV C++ headers: ${_sys_prefix}/include")
                        set(_VULKAN_HPP_FOUND TRUE)
                        set(_SPIRV_HPP_FOUND TRUE)
                        break()
                    endif()
                endforeach()
            endif()

            if(NOT _VULKAN_HPP_FOUND OR NOT _SPIRV_HPP_FOUND)
                message(WARNING "vulkan.hpp/spirv.hpp not found. On macOS: brew install vulkan-headers spirv-headers. Disabling Vulkan.")
                set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
            endif()
        else()
            set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
            message(WARNING "NDK glslc not found at ${_NDK_GLSLC}. Vulkan disabled.")
        endif()
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86" CACHE STRING "" FORCE)
        set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
        set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
        set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    endif()
endif()

# Stable Diffusion backend configuration
set(SD_METAL OFF CACHE BOOL "" FORCE)
# SD_VULKAN is intentionally OFF even when GGML_VULKAN is ON.
# ggml_extend.hpp's GGMLRunner always offloads UNet params to the Vulkan backend at
# inference time (line ~1967: "unet offload params … to runtime backend (Vulkan0)"),
# regardless of load-time config flags like offload_params_to_cpu. On Mali-G715 and
# Adreno, this triggers VK_ERROR_DEVICE_LOST ~2-3s into the first UNet step (GPU TDR).
# No Kotlin/C++ config workaround can prevent it — the only fix is to not compile
# stable-diffusion.cpp with SD_USE_VULKAN.
# GGML_VULKAN remains ON so llama_runner still benefits from Vulkan for LLM inference.
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

target_link_libraries(llama_runner PRIVATE llama llama-common ${log-lib})

if(CMAKE_BUILD_TYPE MATCHES Release)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        target_compile_options(llama_runner PRIVATE -O3 -mtune=cortex-x4)
    else()
        target_compile_options(llama_runner PRIVATE -O3)
    endif()
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
# ggml-vulkan.h needed for ggml_backend_vk_get_device_count() in diffusion_runner_core.cpp
target_include_directories(diffusion_runner PRIVATE "${LLAMA_SRC}/ggml/include")

target_link_libraries(diffusion_runner PRIVATE stable-diffusion ${log-lib})

# Android + GGML_BACKEND_DL=ON: stable-diffusion.cpp calls ggml_backend_cpu_init,
# ggml_backend_is_cpu, ggml_backend_cpu_set_n_threads, ggml_cpu_has_* directly.
# These live in the cpu MODULE which CMake won't let us link transitively.
# Pass the .so file path directly so the linker resolves the symbols and the
# Android dynamic linker loads the lib when diffusion_runner is loaded.
if(GGML_BACKEND_DL)
    if(TARGET ggml-cpu)
        # Standard (non-ALL_VARIANTS) build: single cpu MODULE
        target_link_libraries(diffusion_runner PRIVATE $<TARGET_FILE:ggml-cpu>)
        add_dependencies(diffusion_runner ggml-cpu)
    else()
        # GGML_CPU_ALL_VARIANTS=ON: no single ggml-cpu target; variant targets exist instead.
        # Link the base ARM variant to resolve ggml_backend_cpu_* / ggml_cpu_has_* symbols at
        # link time; GGML's DL dispatch selects the optimal variant at runtime.
        foreach(_cpu_target
                ggml-cpu-android_armv8.0_1
                ggml-cpu-android_armv8.2_1
                ggml-cpu-android_armv8.2_2)
            if(TARGET ${_cpu_target})
                target_link_libraries(diffusion_runner PRIVATE $<TARGET_FILE:${_cpu_target}>)
                add_dependencies(diffusion_runner ${_cpu_target})
                break()
            endif()
        endforeach()
    endif()
endif()

# SD_VULKAN=OFF means diffusion_runner is compiled without SD_USE_VULKAN, so
# ggml-vulkan is NOT linked into diffusion_runner and no Vulkan paths are taken.
# GGML_VULKAN=ON is still used by llama_runner (LLM inference).
# This block is intentionally guarded by SD_VULKAN, not GGML_VULKAN.
if(SD_VULKAN AND TARGET ggml-vulkan)
    target_link_libraries(diffusion_runner PRIVATE $<TARGET_FILE:ggml-vulkan>)
    add_dependencies(diffusion_runner ggml-vulkan)
    target_compile_definitions(diffusion_runner PRIVATE SD_USE_VULKAN)
endif()

if(CMAKE_BUILD_TYPE MATCHES Release)
    target_compile_options(diffusion_runner PRIVATE -O3)
endif()
