# Sondo Kuikly 入口库：libkuikly_entry.so
#   - 导出 NAPI initKuikly（ArkTS: import Napi from 'libkuikly_entry.so'）
#   - 链接 Kuikly 渲染器（@kuikly-open/render 的 native 库）与业务产物 libshared.so
cmake_minimum_required(VERSION 3.5.0)
project(kuikly_entry)

set(KUIKLY_ENTRY_ROOT_PATH ${CMAKE_CURRENT_SOURCE_DIR})
set(CMAKE_CXX_STANDARD 17)
option(SONDO_KUIKLY_HOT_RELOAD "Use the development-only JSVM page runtime" OFF)
if(SONDO_KUIKLY_HOT_RELOAD AND NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    message(FATAL_ERROR "SONDO_KUIKLY_HOT_RELOAD is available only in Debug builds")
endif()

# 由 hvigor 生成：把 oh_modules 里各 HAR 的 native 包（含 @kuikly-open/render）暴露给 CMake
if(DEFINED PACKAGE_FIND_FILE AND EXISTS ${PACKAGE_FIND_FILE})
    include(${PACKAGE_FIND_FILE})
else()
    message(FATAL_ERROR "PACKAGE_FIND_FILE not found. 请先在 entry 目录执行 ohpm install 并通过 DevEco/hvigor 构建")
endif()
if(NOT TARGET render::kuikly)
    find_package(render REQUIRED CONFIG)
endif()

set(SHARED_SO_PATH ${KUIKLY_ENTRY_ROOT_PATH}/../../../libs/${OHOS_ARCH}/libshared.so)
set(SHARED_API_HEADER ${KUIKLY_ENTRY_ROOT_PATH}/thirdparty/biz_entry/libshared_api.h)
if(NOT SONDO_KUIKLY_HOT_RELOAD AND (NOT EXISTS ${SHARED_SO_PATH} OR NOT EXISTS ${SHARED_API_HEADER}))
    message(FATAL_ERROR
        "缺少 Kuikly 业务产物：\n  ${SHARED_SO_PATH}\n  ${SHARED_API_HEADER}\n"
        "请先在工程根目录执行 ./ohos_build.sh")
endif()

add_library(kuikly_entry SHARED napi_init.cpp)
target_include_directories(kuikly_entry PRIVATE ${KUIKLY_ENTRY_ROOT_PATH} ${KUIKLY_ENTRY_ROOT_PATH}/include)

# Kuikly SDK
add_library(kuikly_render ALIAS render::kuikly)

# 业务 so（shared 模块经 Kotlin/Native KBA 工具链编译）
if(SONDO_KUIKLY_HOT_RELOAD)
    target_sources(kuikly_entry PRIVATE hotreload/SondoHotReload.cpp)
    target_compile_definitions(kuikly_entry PRIVATE SONDO_KUIKLY_HOT_RELOAD=1)
    target_link_libraries(kuikly_entry PUBLIC libjsvm.so)
else()
    add_library(kuikly_shared SHARED IMPORTED)
    set_target_properties(kuikly_shared PROPERTIES IMPORTED_LOCATION ${SHARED_SO_PATH})
    target_link_libraries(kuikly_entry PUBLIC kuikly_shared)
endif()

target_link_libraries(kuikly_entry
    PUBLIC
    libace_napi.z.so
    libace_ndk.z.so
    libhilog_ndk.z.so
    kuikly_render
)
