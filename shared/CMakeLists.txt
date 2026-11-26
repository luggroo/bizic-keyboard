cmake_minimum_required(VERSION 3.22.1)
project(tujia_core LANGUAGES CXX)

set(core_sources src/edit.cpp src/lexicon.cpp src/strokes.cpp src/tujia_core.cpp)
if(ANDROID)
    add_library(tujia_core SHARED ${core_sources} ../app/src/main/cpp/jni.cpp)
    # Use stable debug paths so the ELF build ID survives a change of checkout or SDK directory.
    get_filename_component(keyboard_root "${CMAKE_CURRENT_SOURCE_DIR}/.." ABSOLUTE)
    # Dictionary initialization and matching run on every Android ABI, including debug builds.
    target_compile_options(tujia_core PRIVATE -O2
        "-ffile-prefix-map=${keyboard_root}=."
        "-ffile-prefix-map=${CMAKE_ANDROID_NDK}=/android-ndk"
        "-fdebug-compilation-dir=.")
    # Only the core C and JNI interfaces are consumed outside this Android library.
    # Hide static libc++ symbols so the linker can discard its unused sections.
    target_link_options(tujia_core PRIVATE
        "-Wl,--exclude-libs,ALL"
        "-Wl,--gc-sections"
        "-Wl,-z,max-page-size=16384")
    install(TARGETS tujia_core LIBRARY DESTINATION "lib/${ANDROID_ABI}")
else()
    add_library(tujia_core STATIC ${core_sources})
endif()
target_include_directories(tujia_core PUBLIC include PRIVATE src)
target_compile_features(tujia_core PUBLIC cxx_std_17)
