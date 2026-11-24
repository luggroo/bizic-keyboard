cmake_minimum_required(VERSION 3.22.1)
project(tujia_core LANGUAGES CXX)

set(core_sources src/edit.cpp src/lexicon.cpp src/strokes.cpp src/tujia_core.cpp)
if(ANDROID)
    add_library(tujia_core SHARED ${core_sources} ../app/src/main/cpp/jni.cpp)
    # Dictionary initialization and matching run on every supported Android ABI.
    # Keep debug builds responsive on emulators and lower-powered phones.
    target_compile_options(tujia_core PRIVATE -O2)
    target_link_options(tujia_core PRIVATE "-Wl,-z,max-page-size=16384")
    install(TARGETS tujia_core LIBRARY DESTINATION "lib/${ANDROID_ABI}")
else()
    add_library(tujia_core STATIC ${core_sources})
endif()
target_include_directories(tujia_core PUBLIC include PRIVATE src)
target_compile_features(tujia_core PUBLIC cxx_std_17)
