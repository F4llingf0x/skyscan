cmake_minimum_required(VERSION 3.16)
project(skyscan_engine CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(CURL REQUIRED)
find_package(Threads REQUIRED)

add_executable(skyscan-engine src/main.cpp src/pipeline.cpp)
target_include_directories(skyscan-engine PRIVATE ${CMAKE_SOURCE_DIR}/third_party)
target_link_libraries(skyscan-engine PRIVATE CURL::libcurl Threads::Threads)
