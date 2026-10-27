#pragma once

#include "cache_disk_io.hpp"
#include "piece_cache.hpp"

#include <libtorrent/session.hpp>
#include <libtorrent/torrent_handle.hpp>

#ifndef TORRENT_HAS_EVICT_PIECES
#error "libtorrent must carry the eviction patch (patches/libtorrent): a bounded cache cannot work without it"
#endif

#include <atomic>
#include <chrono>
#include <functional>
#include <condition_variable>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace dtv {

// Errors the app tells apart. The numbers are shared with the Java side (TorrentException).
enum class error_kind : int
{
	invalid_handle = 1,
	cancelled = 2,
	closed = 3,
	timeout = 4,
	bad_source = 5,
	metadata_failed = 6,
	piece_too_large = 7,
	cache_too_small = 8,
	low_storage = 9,
	invalid_file = 10,
	torrent_failed = 11,
	engine_failed = 12,
};

struct engine_error : std::runtime_error
{
	engine_error(error_kind k, std::string const& message) : std::runtime_error(message), kind(k) {}
	error_kind kind;
};

struct engine_config
{
	// Private directory for payload cache files. Everything in it is deleted at startup: a previous process
	// may have left files behind.
	std::string cache_dir;
	std::int64_t cache_bytes = std::int64_t(256) << 20;
	// Free space that must remain on the cache's filesystem beyond the cache itself.
	std::int64_t reserve_bytes = std::int64_t(512) << 20;
	int max_piece_bytes = 16 << 20;
	// A cache with fewer slots than this can't hold a read-ahead window and rewind margin.
	int min_slots = 4;
	int connections_limit = 40;
	std::string listen_interfaces = "0.0.0.0:0,[::]:0";
	bool dht = true;
	bool lsd = true;
	bool upnp = false;
	bool natpmp = false;
	bool pex = true;
	// Optional live-test networking: disable LAN discovery and reject non-public peer/tracker/DHT addresses.
	bool public_network_only = false;
	// Tests run several local peers on one address.
	bool multiple_connections_per_ip = false;
	// PEM file with the trust roots for HTTPS trackers and web seeds; empty uses OpenSSL's defaults.
	std::string ca_file;
	std::string user_agent = "Stream";
	int read_ahead_seconds = 45;
	int min_read_ahead_pieces = 2;
	// Assumed until the app knows the media's bitrate (bytes per second).
	std::int64_t default_bitrate = 1250000;
	// Receives diagnostics for the Developer view: peer disconnections and errors. Called on the engine thread.
	std::function<void(std::string const&)> log;
};

// Where a torrent comes from: exactly one of metainfo, magnet or info_hash, plus optional peers to contact.
struct source
{
	std::string metainfo;
	std::string magnet;
	// 40 hex digits (v1) or 64 (v2)
	std::string info_hash;
	std::vector<std::string> trackers;
	// Only explicitly public raw info hashes may use these when no trackers exist.
	std::vector<std::string> fallback_trackers;
	// "address:port", contacted directly
	std::vector<std::string> peers;
};

struct file_info
{
	int index = 0;
	std::string path;
	std::int64_t size = 0;
};

struct torrent_stats
{
	std::string state;
	std::string error;
	int error_kind = 0;
	int peers = 0;
	int seeds = 0;
	int download_rate = 0;
	int upload_rate = 0;
	std::int64_t total_download = 0;
	std::int64_t total_upload = 0;
	int piece_length = 0;
	int num_pieces = 0;
	int hash_failures = 0;
	int evictions_requested = 0;
	int urgent_reservation_failures = 0, obsolete_partials = 0;
	std::int64_t verified_read_bytes = 0;
	piece_cache::stats cache;
	cache_disk_io::stats disk;
};

// One libtorrent session that streams torrents through a bounded cache. Handles are 64-bit ids that are never
// reused, so a stale id is rejected rather than reaching a newer torrent or reader. Every member is thread-safe.
class engine
{
public:
	explicit engine(engine_config cfg);
	~engine();
	void request_stop();
	engine(engine const&) = delete;
	engine& operator=(engine const&) = delete;

	std::uint64_t add(source const& src);
	// Waits for the file list; throws timeout, cancelled, or the torrent's error.
	std::vector<file_info> wait_metadata(std::uint64_t torrent, std::chrono::milliseconds timeout
		, std::atomic<bool> const* cancelled = nullptr);
	// Starts downloading the beginning of this file; other files stay untouched.
	void select_file(std::uint64_t torrent, int file);
	void set_bitrate(std::uint64_t torrent, std::int64_t bytes_per_second);
	void set_demand(std::uint64_t torrent, std::int64_t bytes_per_second, int ahead_seconds);
	std::string metainfo(std::uint64_t torrent);
	// More peers to contact, "address:port".
	void add_peers(std::uint64_t torrent, std::vector<std::string> const& peers);
	torrent_stats stats(std::uint64_t torrent);
	void remove(std::uint64_t torrent);

	std::uint64_t open_reader(std::uint64_t torrent, int file);
	std::int64_t file_size(std::uint64_t reader);
	// Copies verified data at a file position. Returns the bytes copied (at most to the end of a piece), 0 at the
	// end of the file, or would_block when nothing arrived within `wait`. Missing data is waited for and
	// downloaded, never invented.
	static constexpr int would_block = -1;
	int read(std::uint64_t reader, std::int64_t position, char* out, int length, std::chrono::milliseconds wait);
	// Wakes a blocked read, which then throws cancelled; later reads throw too.
	void cancel(std::uint64_t reader);
	void close_reader(std::uint64_t reader);

	static std::string version();

private:
	struct reader;

	struct stream
	{
		std::uint64_t id = 0;
		lt::torrent_handle handle;
		std::condition_variable changed;
		std::shared_ptr<lt::torrent_info const> info;
		// identifies the torrent's storage in the disk subsystem
		lt::sha1_hash info_hash;
		std::shared_ptr<piece_cache> cache;
		std::string error;
		error_kind error_type = error_kind::torrent_failed;
		bool failed = false;
		bool removed = false;
		int file = -1;
		std::int64_t bitrate = 0;
		int ahead_seconds = 0;
		std::atomic<std::int64_t> verified_read_bytes{0};
		std::atomic<int> urgent_reservation_failures{0}, obsolete_partials{0};
		// Where the read-ahead window starts when no reader is active: the last reading position.
		std::int64_t anchor = 0;
		// Only the engine thread touches these two.
		std::vector<lt::download_priority_t> applied;
		std::vector<piece_index_t> deadlines;
		std::atomic<bool> evicting{false};
		// An eviction can reset priorities (a seed's rebuilt picker starts at the default), so they are re-applied.
		std::atomic<bool> priorities_stale{false};
		std::atomic<int> hash_failures{0};
		std::atomic<int> evictions{0};
		std::vector<std::weak_ptr<reader>> readers;
	};

	struct reader
	{
		std::uint64_t id = 0;
		std::weak_ptr<stream> torrent;
		int file = 0;
		std::atomic<bool> cancelled{false};
		std::atomic<std::int64_t> position{0};
		std::atomic<int> waiting{-1};
		std::atomic<std::int64_t> last_read{0};
	};

	std::shared_ptr<stream> find_stream(std::uint64_t id);
	std::shared_ptr<reader> find_reader(std::uint64_t id);
	void fail(stream& t, error_kind kind, std::string const& message);
	void loop();
	void wake();
	void handle_alerts();
	void on_metadata(std::shared_ptr<stream> const& t);
	void update(std::shared_ptr<stream> const& t);
	void evict(std::shared_ptr<stream> const& t, std::vector<piece_index_t> pieces);

	engine_config const m_config;
	std::unique_ptr<lt::session> m_session;
	std::atomic<cache_disk_io*> m_disk{nullptr};

	// Guards streams, readers and stream fields other than atomics. Never held while calling into libtorrent
	// synchronously, so libtorrent's threads never wait for it.
	std::mutex m_mutex;
	std::map<std::uint64_t, std::shared_ptr<stream>> m_streams;
	std::map<std::uint64_t, std::shared_ptr<stream>> m_retiring;
	std::map<std::uint64_t, std::shared_ptr<reader>> m_readers;
	std::uint64_t m_next_id = 1;

	std::mutex m_wake_mutex;
	std::condition_variable m_wake_cv;
	bool m_woken = false;
	std::atomic<bool> m_stop{false};
	std::thread m_thread;
};

} // namespace dtv
