cmake_minimum_required(VERSION 3.22)
project(forge_inference LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(ANDROID AND NOT ANDROID_ABI MATCHES "^(arm64-v8a|x86_64)$")
    message(FATAL_ERROR "Forge inference supports arm64-v8a and x86_64")
endif()

# Static CPU backend; always use the ABI baseline, never the build machine's ISA.
foreach(opt BUILD_SHARED_LIBS GGML_NATIVE GGML_OPENMP GGML_BACKEND_DL
        GGML_CPU_ALL_VARIANTS GGML_BLAS GGML_LLAMAFILE GGML_CPU_KLEIDIAI
        GGML_METAL GGML_VULKAN GGML_OPENCL GGML_CUDA GGML_WEBGPU GGML_RPC
        GGML_ACCELERATE GGML_HIP GGML_SYCL GGML_MUSA
        GGML_SSE42 GGML_AVX GGML_AVX2 GGML_FMA GGML_F16C GGML_BMI2
        GGML_AVX512 GGML_AVX_VNNI LLAMA_CURL LLAMA_BUILD_COMMON
        LLAMA_BUILD_TESTS LLAMA_BUILD_TOOLS LLAMA_BUILD_EXAMPLES LLAMA_BUILD_SERVER)
    set(${opt} OFF CACHE BOOL "" FORCE)
endforeach()
# Leave -march unspecified: the NDK already targets baseline AArch64.
set(GGML_CPU_ARM_ARCH "" CACHE STRING "" FORCE)
include(FetchContent)
set(llama_pin 4762ad7316dcdec20016ab5985fb46a27902204d) # b6000
set(llama_url https://codeload.github.com/ggml-org/llama.cpp/tar.gz/${llama_pin})
# Optional archive cache, e.g. for offline/parallel ABI builds. The hash is
# enforced regardless of which URL is selected.
set(llama_cached "${CMAKE_CURRENT_SOURCE_DIR}/../../../build/downloads/llama-${llama_pin}.tar.gz")
if(EXISTS "${llama_cached}")
    set(llama_url "${llama_cached}")
endif()
FetchContent_Declare(llama_cpp URL "${llama_url}"
    URL_HASH SHA256=06b6a3a1c2d12becc05d02c701274ab1f6d033399b4a20a166b919289e19233c
    TLS_VERIFY ON)
FetchContent_MakeAvailable(llama_cpp)

add_library(forge_inference SHARED forge_jni.cpp)
target_link_libraries(forge_inference PRIVATE llama)
target_link_options(forge_inference PRIVATE "-Wl,--exclude-libs,ALL")
if(NOT ANDROID)
    # Used only by scripts/smoke_native_host.sh (Linux JDK). No JVM linkage:
    # Java calls into this .so and supplies the JNI function table at runtime.
    target_include_directories(forge_inference PRIVATE "$ENV{JAVA_HOME}/include" "$ENV{JAVA_HOME}/include/linux")
endif()
