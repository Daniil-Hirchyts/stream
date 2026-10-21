#pragma once

// Host-only helpers shared by the native tests and the dtv-fixture tool: reproducible content, torrents made from
// it, and a real BitTorrent seeder on loopback. Nothing here reaches the internet or the LAN.

#include <libtorrent/session.hpp>
#include <libtorrent/torrent_handle.hpp>

#include <cstdint>
#include <memory>
#include <string>

namespace dtv::fixtures {

// A new directory under the system's temporary directory, removed with everything in it.
class temp_dir
{
public:
	explicit temp_dir(std::string const& prefix);
	~temp_dir();
	temp_dir(temp_dir const&) = delete;
	temp_dir& operator=(temp_dir const&) = delete;
	std::string const& path() const { return m_path; }
	std::string file(std::string const& name) const { return m_path + "/" + name; }

private:
	std::string m_path;
};

// Content anyone can recompute: byte `offset` of a file made with `seed`.
void fill_content(std::uint32_t seed, std::int64_t offset, char* out, std::size_t size);
// Index of the first byte that differs from the content, or -1.
std::int64_t first_mismatch(std::uint32_t seed, std::int64_t offset, char const* data, std::size_t size);
// Writes `size` bytes of content. The first `hole` bytes stay a sparse hole of zeros, so a test can reach offsets
// beyond 4 GiB without writing them.
void write_content(std::string const& path, std::int64_t size, std::uint32_t seed, std::int64_t hole = 0);

enum class version { v1, v2, hybrid };

// The .torrent for a file or directory, with every piece hashed.
std::string make_torrent(std::string const& path, int piece_size, version v);

struct seeder_options
{
	std::string listen = "127.0.0.1:0";
	// bytes per second; 0 is unlimited
	int upload_rate = 0;
	// Skip checking the files before seeding.
	bool seed_mode = true;
	// Check each piece before serving it; off, corrupted content is served as it is.
	bool verify = true;
};

// A plain libtorrent session seeding one torrent from its files, with DHT, local discovery, port mapping and
// trackers off: a peer finds it only when given its address.
class seeder
{
public:
	seeder(std::string const& torrent, std::string const& save_path, seeder_options const& opts = {});
	~seeder();
	int port() const { return m_port; }
	std::string address() const { return "127.0.0.1:" + std::to_string(m_port); }
	lt::session& session() { return *m_session; }
	lt::torrent_handle const& handle() const { return m_handle; }

private:
	std::unique_ptr<lt::session> m_session;
	lt::torrent_handle m_handle;
	int m_port = 0;
};

} // namespace dtv::fixtures
