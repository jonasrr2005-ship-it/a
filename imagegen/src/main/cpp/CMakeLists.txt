cmake_minimum_required(VERSION 3.22.1)
project("imagegen" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# stable-diffusion.cpp in fester Version; nur das ggml-Submodul wird mitgeladen.
include(FetchContent)
FetchContent_Declare(
        sdcpp
        GIT_REPOSITORY https://github.com/leejet/stable-diffusion.cpp.git
        GIT_TAG master-975-95b3090
        GIT_SHALLOW TRUE
        GIT_SUBMODULES ggml
)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Snapdragon 8 Elite (Galaxy S25)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)
endif()

FetchContent_MakeAvailable(sdcpp)

add_library(imagegen SHARED imagegen.cpp)
target_link_libraries(imagegen PRIVATE stable-diffusion android log)
target_compile_options(imagegen PRIVATE -O3)
# Eigene ggml-Symbole verstecken, damit sie nicht mit llama.cpp kollidieren.
target_link_options(imagegen PRIVATE "-Wl,--exclude-libs,ALL")
