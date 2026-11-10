cmake_minimum_required(VERSION 3.18)
project(dsr_dynrender LANGUAGES CXX)

# Cabecalhos do Vulkan (o workflow clona KhronosGroup/Vulkan-Headers em ./vkh)
set(VULKAN_HEADERS_DIR "${CMAKE_SOURCE_DIR}/vkh" CACHE PATH "Vulkan-Headers")

add_library(VkLayer_dsr_dynrender SHARED layer/dsr_dynrender_layer.cpp)
target_compile_features(VkLayer_dsr_dynrender PRIVATE cxx_std_17)
target_include_directories(VkLayer_dsr_dynrender BEFORE PRIVATE "${VULKAN_HEADERS_DIR}/include")
target_compile_options(VkLayer_dsr_dynrender PRIVATE -fvisibility=hidden -Wall -Wno-unused-function)
target_link_libraries(VkLayer_dsr_dynrender PRIVATE log)
# resultado: libVkLayer_dsr_dynrender.so
