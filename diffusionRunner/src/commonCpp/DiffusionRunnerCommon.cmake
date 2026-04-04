# Source lists and includes for diffusion_runner glue.
# Native build (add_subdirectory stable-diffusion.cpp) lives in :nativeEngine
# (see nativeEngine/src/commonCpp/GgmlUnified.cmake).

include_guard(GLOBAL)

set(DIFFUSION_RUNNER_COMMON_DIR "${CMAKE_CURRENT_LIST_DIR}")
set(SD_SRC "${DIFFUSION_RUNNER_COMMON_DIR}/../../../libraries/stable-diffusion.cpp")

if(NOT EXISTS "${SD_SRC}/CMakeLists.txt")
    message(FATAL_ERROR
        "stable-diffusion.cpp not found at ${SD_SRC}. "
        "Run 'git submodule update --init --recursive'")
endif()

set(DIFFUSION_RUNNER_CORE_SOURCES
    "${DIFFUSION_RUNNER_COMMON_DIR}/diffusion_runner_core.cpp"
)

set(DIFFUSION_RUNNER_JNI_SOURCE
    "${DIFFUSION_RUNNER_COMMON_DIR}/diffusion_runner_jni.cpp"
)

set(DIFFUSION_RUNNER_INCLUDE_DIRS
    "${DIFFUSION_RUNNER_COMMON_DIR}"
    "${SD_SRC}/include"
    "${SD_SRC}"
    "${SD_SRC}/thirdparty"
)