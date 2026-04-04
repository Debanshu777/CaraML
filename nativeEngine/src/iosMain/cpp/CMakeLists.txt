cmake_minimum_required(VERSION 3.22.1)
project(llama_runner VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_BUILD_COMMON ON CACHE BOOL "Build common utils" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static libraries for iOS" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

# Stable Diffusion backend configuration
set(SD_METAL ON CACHE BOOL "" FORCE)
set(SD_VULKAN OFF CACHE BOOL "" FORCE)

include("${CMAKE_CURRENT_LIST_DIR}/../../commonCpp/GgmlUnified.cmake")
include("${CMAKE_CURRENT_LIST_DIR}/../../../../runner/src/commonCpp/LlamaRunnerCommon.cmake")

add_library(
    llama_runner
    STATIC
    ${LLAMA_RUNNER_CORE_SOURCES}
    "${CMAKE_CURRENT_LIST_DIR}/../../../../runner/src/iosMain/cpp/llama_runner.cpp"
)

target_include_directories(llama_runner PRIVATE ${LLAMA_RUNNER_INCLUDE_DIRS})

target_link_libraries(llama_runner PRIVATE llama common)

if(CMAKE_BUILD_TYPE MATCHES Release)
    target_compile_options(llama_runner PRIVATE -O3)
endif()

# --- diffusion_runner (stable-diffusion.cpp wrapper) ---
include("${CMAKE_CURRENT_LIST_DIR}/../../../../diffusionRunner/src/commonCpp/DiffusionRunnerCommon.cmake")

add_library(
    diffusion_runner
    STATIC
    ${DIFFUSION_RUNNER_CORE_SOURCES}
    "${CMAKE_CURRENT_LIST_DIR}/../../../../diffusionRunner/src/iosMain/cpp/diffusion_runner.cpp"
)

target_include_directories(diffusion_runner PRIVATE
    ${DIFFUSION_RUNNER_INCLUDE_DIRS}
    "${CMAKE_CURRENT_LIST_DIR}/../../../../diffusionRunner/src/iosMain/cpp"
)
target_link_libraries(diffusion_runner PRIVATE stable-diffusion)

if(CMAKE_BUILD_TYPE MATCHES Release)
    target_compile_options(diffusion_runner PRIVATE -O3)
endif()
