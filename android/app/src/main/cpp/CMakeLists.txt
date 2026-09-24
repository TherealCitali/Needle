cmake_minimum_required(VERSION 3.22.1)
# The Needle engine archive is C++ (it uses the C++ standard library), while the
# JNI bridge itself is plain C. Enabling CXX here keeps CMake linking the final
# shared object with the C++ driver, so libc++ is linked in as well.
project(needlejni C CXX)

# ---------------------------------------------------------------------------
# Needle engine (Cactus Compute, Apache-2.0) - https://github.com/cactus-compute/needle
#
# Cactus publishes the Android engine as a static archive per ABI. It is fetched
# here at configure time, checksum-verified, and linked into libneedlejni.so, so
# the APK carries the runtime while the weights stay a one-time download.
#
# A CI job (or a developer) can pre-place the archives at
#   cpp/prebuilt/<abi>/libneedle.a
# and this file will use them instead of downloading.
# ---------------------------------------------------------------------------
if(NOT DEFINED NEEDLE_ENGINE_VERSION)
    set(NEEDLE_ENGINE_VERSION "3.0.2")
endif()
if(NOT DEFINED NEEDLE_ALLOW_STUB)
    set(NEEDLE_ALLOW_STUB OFF)
endif()

set(NEEDLE_HF_BASE "https://huggingface.co/Cactus-Compute/needle3/resolve/main")

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(NEEDLE_PLATFORM "android-arm64")
    set(NEEDLE_LIB_SHA256 "5e0a5daaadca1fbe1c518110bee6bbb1cc97a5e53a16a1e964d0af0186eac60a")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(NEEDLE_PLATFORM "android-armv7")
    set(NEEDLE_LIB_SHA256 "40e8dcf4440281fc248302c25f0ddc87fcc9d52a210a216f06f2aad3de42c86c")
elseif(ANDROID_ABI STREQUAL "x86_64" OR ANDROID_ABI STREQUAL "x86" OR ANDROID_ABI STREQUAL "riscv64")
    set(NEEDLE_PLATFORM "android-${ANDROID_ABI}")
    message(WARNING "Needle publishes no engine for ${ANDROID_ABI}; no input tools will be available on that ABI.")
else()
    message(FATAL_ERROR "Unknown ANDROID_ABI '${ANDROID_ABI}'.")
endif()

set(NEEDLE_STUB OFF)
set(NEEDLE_PREBUILT "${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}/libneedle.a")

if(NOT EXISTS "${NEEDLE_PREBUILT}")
    if(NEEDLE_PLATFORM MATCHES "^android-(arm64|armv7)$")
        set(NEEDLE_LIB_URL "${NEEDLE_HF_BASE}/${NEEDLE_PLATFORM}/libneedle.a?download=true")
        message(STATUS "Fetching Needle ${NEEDLE_ENGINE_VERSION} engine for ${ANDROID_ABI} from ${NEEDLE_LIB_URL}")
        file(DOWNLOAD "${NEEDLE_LIB_URL}" "${NEEDLE_PREBUILT}"
             EXPECTED_HASH SHA256=${NEEDLE_LIB_SHA256}
             TIMEOUT 300
             STATUS NEEDLE_DOWNLOAD_STATUS)
        list(GET NEEDLE_DOWNLOAD_STATUS 0 NEEDLE_DOWNLOAD_CODE)
        if(NOT NEEDLE_DOWNLOAD_CODE EQUAL 0)
            list(GET NEEDLE_DOWNLOAD_STATUS 1 NEEDLE_DOWNLOAD_MESSAGE)
            file(REMOVE "${NEEDLE_PREBUILT}")
            if(NEEDLE_ALLOW_STUB)
                message(WARNING "Needle engine download failed (${NEEDLE_DOWNLOAD_MESSAGE}); "
                                "building with the stub engine, on-device inference will be unavailable.")
                set(NEEDLE_STUB ON)
            else()
                message(FATAL_ERROR
                    "Could not download the Needle engine (${NEEDLE_DOWNLOAD_MESSAGE}).\n"
                    "Pre-place it at ${NEEDLE_PREBUILT} or configure with -DNEEDLE_ALLOW_STUB=ON.")
            endif()
        endif()
    else()
        set(NEEDLE_STUB ON)
        message(WARNING "No prebuilt Needle engine for ${ANDROID_ABI}; on-device inference will be unavailable on that ABI.")
    endif()
endif()

add_library(needlejni SHARED needle_jni.c needle_stl_shim.cpp)
target_include_directories(needlejni PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")

if(NEEDLE_STUB)
    target_compile_definitions(needlejni PRIVATE NEEDLE_ENGINE_AVAILABLE=0)
else()
    target_compile_definitions(needlejni PRIVATE NEEDLE_ENGINE_AVAILABLE=1)
    add_library(needle_engine STATIC IMPORTED)
    set_target_properties(needle_engine PROPERTIES IMPORTED_LOCATION "${NEEDLE_PREBUILT}")
    target_link_libraries(needlejni needle_engine)
endif()

# libneedle.a is compiled against the C++ standard library, so the shared object
# has to link it explicitly (the C++ driver would do this for a C++ target, but
# the JNI sources are C).
target_link_libraries(needlejni c++_shared)

find_library(log-lib log)
find_library(android-lib android)
find_library(dl-lib dl)
find_library(m-lib m)
find_library(z-lib z)

target_link_libraries(needlejni ${log-lib})
foreach(extra_lib ${android-lib} ${dl-lib} ${m-lib} ${z-lib})
    if(extra_lib)
        target_link_libraries(needlejni ${extra_lib})
    endif()
endforeach()
