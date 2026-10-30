cmake_minimum_required(VERSION 3.22)
project(stream_native LANGUAGES C CXX)

# The streaming engine: libtorrent with a bounded piece cache, for the Android app (JNI) and for host tools
# and tests. Sources come from fetch-deps.sh, OpenSSL from build-openssl.sh; see README.md.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(DTV_DEPS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/.deps" CACHE PATH "Fetched native sources (fetch-deps.sh)")
set(DTV_OPENSSL_ROOT "" CACHE PATH "OpenSSL built by build-openssl.sh for this target")
option(DTV_HOST "Build the host fixture tool and tests" OFF)
option(DTV_JNI "Build the Android JNI library" OFF)
set(DTV_SANITIZE "" CACHE STRING "Sanitizers for host builds: 'address,undefined' or 'thread'")
option(DTV_LIBTORRENT_CHECKS "Build libtorrent with asserts and invariant checks" OFF)

foreach(dep libtorrent boost openssl)
	if(NOT EXISTS "${DTV_DEPS_DIR}/${dep}/.pinned")
		message(FATAL_ERROR "${dep} sources are missing from ${DTV_DEPS_DIR}: run fetch-deps.sh")
	endif()
endforeach()
if(NOT DTV_OPENSSL_ROOT AND ANDROID_ABI)
	# The Android Gradle build runs build-openssl.sh for every ABI first.
	set(DTV_OPENSSL_ROOT "${DTV_DEPS_DIR}/build/openssl/${ANDROID_ABI}")
endif()
if(NOT EXISTS "${DTV_OPENSSL_ROOT}/lib/libcrypto.a")
	message(FATAL_ERROR "No OpenSSL in '${DTV_OPENSSL_ROOT}': run build-openssl.sh for this target")
endif()

# Every patch in patches/libtorrent must be applied (fetch-deps.sh does this); eviction depends on it.
file(READ "${DTV_DEPS_DIR}/libtorrent/include/libtorrent/torrent_handle.hpp" dtv_handle_header)
if(NOT dtv_handle_header MATCHES "TORRENT_HAS_EVICT_PIECES")
	message(FATAL_ERROR "libtorrent lacks the eviction patch: rerun fetch-deps.sh")
endif()

if(DTV_SANITIZE)
	add_compile_options(-fsanitize=${DTV_SANITIZE} -fno-omit-frame-pointer -fno-sanitize-recover=all)
	add_link_options(-fsanitize=${DTV_SANITIZE})
endif()

if(ANDROID)
	# Bionic has aligned_alloc only from API 28; the app runs from API 26.
	add_compile_definitions(BOOST_ASIO_DISABLE_STD_ALIGNED_ALLOC)
endif()

# Boost is used as headers only. Its source release has no CMake package, so a small one is provided.
set(DTV_BOOST_INCLUDE "${DTV_DEPS_DIR}/boost")
set(Boost_DIR "${CMAKE_CURRENT_SOURCE_DIR}/cmake/boost" CACHE PATH "" FORCE)

# OpenSSL is linked statically. NDK toolchains search only their sysroot unless the root is listed.
set(OPENSSL_ROOT_DIR "${DTV_OPENSSL_ROOT}")
set(OPENSSL_USE_STATIC_LIBS TRUE)
list(APPEND CMAKE_FIND_ROOT_PATH "${DTV_OPENSSL_ROOT}")
find_package(OpenSSL REQUIRED)

# libtorrent: static, no DHT-independent extras the app doesn't use. Logging stays on for diagnostics.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(webtorrent OFF CACHE BOOL "" FORCE)
set(i2p OFF CACHE BOOL "" FORCE)
set(deprecated-functions OFF CACHE BOOL "" FORCE)
set(mutable-torrents OFF CACHE BOOL "" FORCE)
set(gnutls OFF CACHE BOOL "" FORCE)
set(build_tests OFF CACHE BOOL "" FORCE)
set(build_examples OFF CACHE BOOL "" FORCE)
set(build_tools OFF CACHE BOOL "" FORCE)
set(python-bindings OFF CACHE BOOL "" FORCE)
if(DTV_LIBTORRENT_CHECKS)
	set(developer-options ON CACHE BOOL "" FORCE)
	set(asserts on CACHE STRING "" FORCE)
	set(invariant-checks full CACHE STRING "" FORCE)
endif()
# Boost comes from cmake/boost as a package, never through the removed FindBoost module.
set(CMAKE_POLICY_DEFAULT_CMP0167 NEW)
add_subdirectory("${DTV_DEPS_DIR}/libtorrent" libtorrent EXCLUDE_FROM_ALL)
# Third-party code: its warnings are upstream's to fix, and would hide ours.
target_compile_options(torrent-rasterbar PRIVATE -w)

# The Rust engine core (core/, pinned by core/rust-toolchain.toml and core/Cargo.lock), built by Cargo for this
# target and linked statically. Its piece cache stores every torrent's pieces (see src/piece_cache.hpp).
find_program(DTV_CARGO cargo HINTS "$ENV{HOME}/.cargo/bin" REQUIRED)
if(ANDROID_ABI STREQUAL "arm64-v8a")
	set(dtv_rust_target aarch64-linux-android)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
	set(dtv_rust_target armv7-linux-androideabi)
elseif(ANDROID_ABI STREQUAL "x86_64")
	set(dtv_rust_target x86_64-linux-android)
elseif(ANDROID_ABI)
	message(FATAL_ERROR "No Rust target for ${ANDROID_ABI}")
endif()
set(dtv_rust_dir "${CMAKE_CURRENT_BINARY_DIR}/rust")
if(dtv_rust_target)
	set(dtv_rust_lib "${dtv_rust_dir}/${dtv_rust_target}/release/libstream_core.a")
	set(dtv_rust_target_args --target ${dtv_rust_target})
else()
	set(dtv_rust_lib "${dtv_rust_dir}/release/libstream_core.a")
	set(dtv_rust_target_args)
endif()
file(GLOB_RECURSE dtv_rust_sources CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/core/src/*.rs")
add_custom_command(OUTPUT "${dtv_rust_lib}"
	# Run from core/ so rustup picks the pinned toolchain; --locked keeps the reviewed dependency versions.
	COMMAND "${CMAKE_COMMAND}" -E env "CARGO_TARGET_DIR=${dtv_rust_dir}"
		"${DTV_CARGO}" build --release --locked --no-default-features ${dtv_rust_target_args}
	WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/core"
	DEPENDS ${dtv_rust_sources} core/Cargo.toml core/Cargo.lock core/rust-toolchain.toml
	COMMENT "Building the Rust engine core"
	VERBATIM)
add_custom_target(dtv_rust_core DEPENDS "${dtv_rust_lib}")
add_library(stream_core STATIC IMPORTED GLOBAL)
set_target_properties(stream_core PROPERTIES IMPORTED_LOCATION "${dtv_rust_lib}")
add_dependencies(stream_core dtv_rust_core)
if(APPLE)
	set_property(TARGET stream_core APPEND PROPERTY INTERFACE_LINK_LIBRARIES iconv)
elseif(ANDROID)
	set_property(TARGET stream_core APPEND PROPERTY INTERFACE_LINK_LIBRARIES log dl m)
else()
	set_property(TARGET stream_core APPEND PROPERTY INTERFACE_LINK_LIBRARIES pthread dl m)
endif()

add_library(dtv_engine STATIC
	src/piece_cache.cpp
	src/cache_disk_io.cpp
	src/engine.cpp
)
target_include_directories(dtv_engine PUBLIC src include)
target_link_libraries(dtv_engine PUBLIC torrent-rasterbar stream_core)
target_compile_options(dtv_engine PRIVATE -Wall -Wextra -Werror)
# 64-bit file offsets on every ABI, including 32-bit ARM.
target_compile_definitions(dtv_engine PUBLIC _FILE_OFFSET_BITS=64)

if(DTV_JNI)
	add_library(stream SHARED jni/jni_bridge.cpp)
	target_link_libraries(stream PRIVATE dtv_engine log)
	target_compile_options(stream PRIVATE -Wall -Wextra -Werror)
	# Only the JNI entry points are exported; libc++ is linked statically by the NDK's c++_static runtime.
	target_link_options(stream PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)
endif()

if(DTV_HOST)
	add_library(dtv_fixtures STATIC tools/fixtures.cpp)
	target_include_directories(dtv_fixtures PUBLIC tools)
	target_link_libraries(dtv_fixtures PUBLIC torrent-rasterbar)

	add_executable(dtv-fixture tools/dtv_fixture.cpp)
	target_link_libraries(dtv-fixture PRIVATE dtv_fixtures)

	enable_testing()
	add_executable(dtv-tests
		tests/test_main.cpp
		tests/test_piece_cache.cpp
		tests/test_eviction.cpp
		tests/test_streaming.cpp
        tests/test_startup.cpp
	)
	target_include_directories(dtv-tests PRIVATE tests)
	target_link_libraries(dtv-tests PRIVATE dtv_engine dtv_fixtures)
	target_compile_options(dtv-tests PRIVATE -Wall -Wextra -Werror)
	add_test(NAME dtv-tests COMMAND dtv-tests)
endif()
