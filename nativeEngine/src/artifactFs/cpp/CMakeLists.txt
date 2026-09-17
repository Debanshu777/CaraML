cmake_minimum_required(VERSION 3.22.1)
project(artifact_fs VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT ANDROID)
    set(ARTIFACT_FS_JNI_INCLUDE_DIRS "")
    if(DEFINED ENV{JAVA_HOME})
        set(_jni_include "$ENV{JAVA_HOME}/include")
        if(EXISTS "${_jni_include}/jni.h")
            list(APPEND ARTIFACT_FS_JNI_INCLUDE_DIRS "${_jni_include}")
            if(APPLE)
                list(APPEND ARTIFACT_FS_JNI_INCLUDE_DIRS "${_jni_include}/darwin")
            elseif(UNIX)
                list(APPEND ARTIFACT_FS_JNI_INCLUDE_DIRS "${_jni_include}/linux")
            elseif(WIN32)
                list(APPEND ARTIFACT_FS_JNI_INCLUDE_DIRS "${_jni_include}/win32")
            endif()
        endif()
    endif()
    if(ARTIFACT_FS_JNI_INCLUDE_DIRS STREQUAL "")
        message(FATAL_ERROR "artifact_fs requires a full JDK with JNI headers")
    endif()
endif()

if(WIN32)
    add_library(artifact_fs SHARED artifact_fs_jni_windows.cpp)
else()
    add_library(artifact_fs SHARED artifact_fs_jni.cpp)
endif()
target_include_directories(artifact_fs PRIVATE ${ARTIFACT_FS_JNI_INCLUDE_DIRS})
set_target_properties(artifact_fs PROPERTIES
    OUTPUT_NAME "artifact_fs"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
)
foreach(_artifact_fs_config DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
    set_target_properties(artifact_fs PROPERTIES
        "LIBRARY_OUTPUT_DIRECTORY_${_artifact_fs_config}" "${CMAKE_BINARY_DIR}"
        "RUNTIME_OUTPUT_DIRECTORY_${_artifact_fs_config}" "${CMAKE_BINARY_DIR}"
    )
endforeach()

if(MSVC)
    target_compile_options(artifact_fs PRIVATE /W4 /WX)
else()
    target_compile_options(artifact_fs PRIVATE -Wall -Wextra -Werror)
endif()

if(BUILD_TESTING AND NOT WIN32 AND NOT ANDROID)
    add_executable(artifact_fs_android_root_test artifact_fs_android_root_test.cpp)
    target_include_directories(artifact_fs_android_root_test PRIVATE ${ARTIFACT_FS_JNI_INCLUDE_DIRS})
    target_compile_options(artifact_fs_android_root_test PRIVATE -Wall -Wextra -Werror)
    add_test(NAME artifact_fs_android_root_test COMMAND artifact_fs_android_root_test)
    add_executable(artifact_fs_append_test artifact_fs_append_test.cpp)
    target_include_directories(artifact_fs_append_test PRIVATE ${ARTIFACT_FS_JNI_INCLUDE_DIRS})
    target_compile_options(artifact_fs_append_test PRIVATE -Wall -Wextra -Werror)
    add_test(NAME artifact_fs_append_test COMMAND artifact_fs_append_test)
endif()
