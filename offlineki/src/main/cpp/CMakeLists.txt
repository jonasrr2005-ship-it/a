cmake_minimum_required(VERSION 3.22.1)
project("offlineki" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)   # statische llama-Libs in unsere .so linken

# llama.cpp wird beim Bauen in einer festen Version heruntergeladen.
# Die fertige App selbst braucht danach nie Internet.
set(LLAMA_CPP_TAG "b12000" CACHE STRING "llama.cpp Release-Tag")

include(FetchContent)
FetchContent_Declare(
        llama
        URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/${LLAMA_CPP_TAG}.tar.gz
)

# Nur die Bibliothek, keine Tools/Server/Tests.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Snapdragon 8 Elite (Samsung Galaxy S25): ARMv8.7 mit dotprod, i8mm und fp16 –
    # beschleunigt die quantisierten Matrixmultiplikationen deutlich.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)
endif()

FetchContent_MakeAvailable(llama)

add_library(offlineki SHARED llm.cpp)
target_link_libraries(offlineki PRIVATE llama android log)
target_compile_options(offlineki PRIVATE -O3)
