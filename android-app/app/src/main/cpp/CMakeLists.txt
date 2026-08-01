cmake_minimum_required(VERSION 3.22.1)

project(mobilecore_llama_bridge)

# Android instrumentation uses the debug variant, but G2D performance data
# must exercise production-equivalent optimized kernels rather than ggml -O0.
set(CMAKE_C_FLAGS_DEBUG "-O3 -g0 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -g0 -DNDEBUG")

set(LLAMA_CPP_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS_3RD_PARTY OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS_3RD_PARTY OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_ACCELERATE OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
set(LLAMA_INSTALL_VERSION "0.0.0")

add_subdirectory("${LLAMA_CPP_ROOT}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp")

# Build libmtmd itself without registering its desktop CLI/debug executables as
# Android native outputs. Android Gradle otherwise attempts to compile those
# tools, which depend on llama-common and are not part of the app runtime.
set(MTMD_ROOT "${LLAMA_CPP_ROOT}/tools/mtmd")
file(GLOB MTMD_MODEL_SOURCES CONFIGURE_DEPENDS "${MTMD_ROOT}/models/*.cpp")
add_library(
    mtmd
    STATIC
    "${MTMD_ROOT}/mtmd.cpp"
    "${MTMD_ROOT}/mtmd-audio.cpp"
    "${MTMD_ROOT}/mtmd-image.cpp"
    "${MTMD_ROOT}/mtmd-helper.cpp"
    "${MTMD_ROOT}/clip.cpp"
    ${MTMD_MODEL_SOURCES}
)
set_target_properties(mtmd PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_features(mtmd PRIVATE cxx_std_17)
target_include_directories(
    mtmd
    PUBLIC "${MTMD_ROOT}"
    PRIVATE "${LLAMA_CPP_ROOT}" "${LLAMA_CPP_ROOT}/vendor"
)
find_package(Threads REQUIRED)
target_link_libraries(mtmd PUBLIC ggml llama PRIVATE Threads::Threads)
target_compile_options(mtmd PRIVATE -Wno-cast-qual -Wno-missing-prototypes)

add_library(
    mobilecore_llama
    SHARED
    mobilecore_llama_bridge.cpp
)

target_compile_features(mobilecore_llama PRIVATE cxx_std_17)
target_compile_definitions(mobilecore_llama PRIVATE MOBILECORE_USE_LLAMA_CPP=1)
target_include_directories(
    mobilecore_llama
    PRIVATE
    "${LLAMA_CPP_ROOT}/tools/mtmd"
)

find_library(log-lib log)

target_link_libraries(
    mobilecore_llama
    llama
    mtmd
    ${log-lib}
)
