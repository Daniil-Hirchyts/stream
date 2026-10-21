#pragma once

#include "piece_cache.hpp"

#include <libtorrent/aux_/vector.hpp>
#include <libtorrent/disk_buffer_holder.hpp>
#include <libtorrent/disk_interface.hpp>
#include <libtorrent/disk_observer.hpp>
#include <libtorrent/info_hash.hpp>
#include <libtorrent/io_context.hpp>
#include <libtorrent/peer_request.hpp>
#include <libtorrent/session_params.hpp>

#include <algorithm>
#include <condition_variable>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace dtv {

// Leave room for filesystem allocation metadata (ext4 includes extent blocks in st_blocks).
constexpr std::int64_t cache_storage_overhead = 64 << 10;
inline int cache_slots(std::int64_t bytes, int piece_length)
{
	return int(std::max<std::int64_t>(0, bytes - cache_storage_overhead) / piece_length);
}

struct cache_config
{
	// Private directory for cache files; nothing else in it is touched.
	std::string directory;
	// Total allocated storage per torrent, including filesystem overhead. The engine refuses budgets that
	// cannot hold enough pieces to stream.
	std::int64_t cache_bytes = std::int64_t(256) << 20;
	// Blocks waiting to be written beyond this stop peers from sending more until the queue drains.
	std::int64_t write_queue_bytes = std::int64_t(8) << 20;
};

// libtorrent's disk subsystem for streaming: every torrent's pieces live in a bounded piece_cache rather than in
// its files. One worker runs writes, reads, clears and releases in order; another computes hashes, each once its
// piece or block is written, since libtorrent asks for a hash as soon as it has queued the last write. Writes for a
// piece with no free slot wait, holding back their peers, until eviction frees one.
class cache_disk_io final : public lt::disk_interface, public lt::buffer_allocator_interface
{
public:
	cache_disk_io(lt::io_context& ioc, cache_config cfg);
	~cache_disk_io() override;

	// The cache of the torrent with this info hash (info_hash_t::get_best()), or null before it has metadata.
	std::shared_ptr<piece_cache> find(lt::sha1_hash const& info_hash) const;
	// The torrent forgot these pieces (torrent_handle::evict_pieces). They are freed after every disk job queued
	// before this call, and once no reader pins them.
	void release(std::shared_ptr<piece_cache> cache, std::vector<piece_index_t> pieces);

	struct stats
	{
		std::int64_t buffer_bytes = 0;
		std::int64_t queued_write_bytes = 0;
		int blocked_writes = 0;
	};
	stats get_stats() const;

	// disk_interface
	lt::storage_holder new_torrent(lt::storage_params const& params, std::shared_ptr<void> const&) override;
	void remove_torrent(lt::storage_index_t idx) override;
	void async_read(lt::storage_index_t storage, lt::peer_request const& r
		, std::function<void(lt::disk_buffer_holder, lt::storage_error const&)> handler
		, lt::disk_job_flags_t flags) override;
	bool async_write(lt::storage_index_t storage, lt::peer_request const& r, char const* buf
		, std::shared_ptr<lt::disk_observer> o, std::function<void(lt::storage_error const&)> handler
		, lt::disk_job_flags_t flags) override;
	void async_hash(lt::storage_index_t storage, lt::piece_index_t piece, lt::span<lt::sha256_hash> v2
		, lt::disk_job_flags_t flags
		, std::function<void(lt::piece_index_t, lt::sha1_hash const&, lt::storage_error const&)> handler) override;
	void async_hash2(lt::storage_index_t storage, lt::piece_index_t piece, int offset, lt::disk_job_flags_t flags
		, std::function<void(lt::piece_index_t, lt::sha256_hash const&, lt::storage_error const&)> handler) override;
	void async_move_storage(lt::storage_index_t storage, std::string p, lt::move_flags_t flags
		, std::function<void(lt::status_t, std::string const&, lt::storage_error const&)> handler) override;
	void async_release_files(lt::storage_index_t storage, std::function<void()> handler) override;
	void async_check_files(lt::storage_index_t storage, lt::add_torrent_params const* resume_data
		, lt::aux::vector<std::string, lt::file_index_t> links
		, std::function<void(lt::status_t, lt::storage_error const&)> handler) override;
	void async_stop_torrent(lt::storage_index_t storage, std::function<void()> handler) override;
	void async_rename_file(lt::storage_index_t storage, lt::file_index_t index, std::string name
		, std::function<void(std::string const&, lt::file_index_t, lt::storage_error const&)> handler) override;
	void async_delete_files(lt::storage_index_t storage, lt::remove_flags_t options
		, std::function<void(lt::storage_error const&)> handler) override;
	void async_set_file_priority(lt::storage_index_t storage
		, lt::aux::vector<lt::download_priority_t, lt::file_index_t> prio
		, std::function<void(lt::storage_error const&
			, lt::aux::vector<lt::download_priority_t, lt::file_index_t>)> handler) override;
	void async_clear_piece(lt::storage_index_t storage, lt::piece_index_t index
		, std::function<void(lt::piece_index_t)> handler) override;
	void update_stats_counters(lt::counters& c) const override;
	std::vector<lt::open_file_state> get_status(lt::storage_index_t) const override;
	void abort(bool wait) override;
	void submit_jobs() override;
	void settings_updated() override;

	// buffer_allocator_interface: buffers handed to libtorrent by async_read()
	void free_disk_buffer(char* b) override;
	void free_multiple_buffers(lt::span<char*> bufs) override;
#if TORRENT_DEBUG_BUFFER_POOL
	void rename_buffer(char*, char const*) override {}
#endif

private:
	struct storage
	{
		std::shared_ptr<piece_cache> cache;
		lt::sha1_hash info_hash;
		// v2 piece sizes exclude trailing padding; empty for v1-only torrents
		std::vector<int> piece_sizes2;
	};

	// A hash waiting for its piece (offset < 0) or block to be written.
	struct parked_hash
	{
		std::shared_ptr<piece_cache> cache;
		piece_index_t piece;
		int offset;
		std::function<void()> job;
	};

	struct blocked_write
	{
		std::shared_ptr<piece_cache> cache;
		lt::peer_request request;
		std::shared_ptr<char[]> data;
		std::function<void(lt::storage_error const&)> handler;
	};

	std::shared_ptr<storage> get(lt::storage_index_t idx) const;
	void push_disk(std::function<void()> job);
	void push_hash(std::function<void()> job);
	void run(std::deque<std::function<void()>>& jobs, std::condition_variable& cv, bool hashes);
	void finish_write(int length, std::function<void(lt::storage_error const&)> const& handler
		, lt::storage_error const& error);
	void retry_blocked();
	void hash_when_written(std::shared_ptr<piece_cache> const& cache, piece_index_t piece, int offset
		, std::function<void()> job);
	void hash_parked(piece_cache const* cache, piece_index_t piece);
	void schedule_retry();
	char* allocate(int size);

	lt::io_context& m_ioc;
	// Disk/hash completions must run before libtorrent destroys its peer allocator.
	boost::asio::executor_work_guard<lt::io_context::executor_type> m_work;
	cache_config const m_config;
	std::uint64_t m_next_file = 0;

	mutable std::mutex m_mutex;
	lt::aux::vector<std::shared_ptr<storage>, lt::storage_index_t> m_storages;
	std::vector<lt::storage_index_t> m_free_storages;
	std::deque<std::function<void()>> m_disk_jobs;
	std::deque<std::function<void()>> m_hash_jobs;
	std::condition_variable m_disk_cv;
	std::condition_variable m_hash_cv;
	std::deque<blocked_write> m_blocked;
	std::vector<parked_hash> m_parked;
	bool m_retry_scheduled = false;
	std::vector<std::shared_ptr<lt::disk_observer>> m_observers;
	std::int64_t m_queued_write_bytes = 0;
	std::int64_t m_buffer_bytes = 0;
	bool m_abort = false;
	bool m_disk_done = false;
	int m_workers = 2;
	std::thread m_disk_thread;
	std::thread m_hash_thread;
};

// For session_params::disk_io_constructor. created() receives the disk subsystem once the session builds it.
lt::disk_io_constructor_type cache_disk_io_constructor(cache_config cfg
	, std::function<void(cache_disk_io*)> created);

} // namespace dtv
