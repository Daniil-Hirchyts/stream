#include "cache_disk_io.hpp"

#include <libtorrent/add_torrent_params.hpp>
#include <libtorrent/file_storage.hpp>
#include <libtorrent/hasher.hpp>
#include <libtorrent/hex.hpp>
#include <libtorrent/operations.hpp>

#include <boost/asio/post.hpp>

#include <algorithm>
#include <cstring>

namespace dtv {

namespace {

// Each buffer handed to libtorrent records its size ahead of the data, for the memory budget.
constexpr std::size_t header = alignof(std::max_align_t);

lt::storage_error aborted(lt::operation_t op)
{
	lt::storage_error error;
	error.ec = boost::asio::error::operation_aborted;
	error.operation = op;
	return error;
}

} // namespace

cache_disk_io::cache_disk_io(lt::io_context& ioc, cache_config cfg)
	: m_ioc(ioc)
	, m_work(boost::asio::make_work_guard(ioc))
	, m_config(std::move(cfg))
{
	m_disk_thread = std::thread([this] { run(m_disk_jobs, m_disk_cv, false); });
	m_hash_thread = std::thread([this] { run(m_hash_jobs, m_hash_cv, true); });
}

cache_disk_io::~cache_disk_io()
{
	abort(true);
}

void cache_disk_io::run(std::deque<std::function<void()>>& jobs, std::condition_variable& cv, bool const hashes)
{
	for (;;)
	{
		std::function<void()> job;
		{
			std::unique_lock<std::mutex> lock(m_mutex);
			cv.wait(lock, [&] { return !jobs.empty() || (m_abort && (!hashes || m_disk_done)); });
			// Queued jobs still run after abort, so their handlers are not lost.
			if (jobs.empty())
			{
				if (!hashes) { m_disk_done = true; m_hash_cv.notify_all(); }
				if (--m_workers == 0) m_work.reset();
				return;
			}
			job = std::move(jobs.front());
			jobs.pop_front();
		}
		job();
	}
}

// libtorrent batches jobs and calls submit_jobs(), but not on every path; waking the worker here costs little.
void cache_disk_io::push_disk(std::function<void()> job)
{
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		m_disk_jobs.push_back(std::move(job));
	}
	m_disk_cv.notify_one();
}

void cache_disk_io::push_hash(std::function<void()> job)
{
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		m_hash_jobs.push_back(std::move(job));
	}
	m_hash_cv.notify_one();
}

void cache_disk_io::submit_jobs()
{
	m_disk_cv.notify_one();
	m_hash_cv.notify_one();
}

std::shared_ptr<cache_disk_io::storage> cache_disk_io::get(lt::storage_index_t const idx) const
{
	std::lock_guard<std::mutex> lock(m_mutex);
	if (idx < lt::storage_index_t{0} || idx >= m_storages.end_index()) return {};
	return m_storages[idx];
}

std::shared_ptr<piece_cache> cache_disk_io::find(lt::sha1_hash const& info_hash) const
{
	std::lock_guard<std::mutex> lock(m_mutex);
	for (auto const& s : m_storages)
		if (s && s->info_hash == info_hash) return s->cache;
	return {};
}

lt::storage_holder cache_disk_io::new_torrent(lt::storage_params const& params, std::shared_ptr<void> const&)
{
	lt::file_storage const& fs = params.files;
	int const pieces = fs.num_pieces();
	int const piece_length = fs.piece_length();
	auto s = std::make_shared<storage>();
	s->info_hash = params.info_hash;

	std::vector<int> sizes(static_cast<std::size_t>(pieces), 0);
	for (lt::piece_index_t p : fs.piece_range()) sizes[std::size_t(static_cast<int>(p))] = fs.piece_size(p);
	if (params.v2)
	{
		s->piece_sizes2.resize(static_cast<std::size_t>(pieces));
		for (lt::piece_index_t p : fs.piece_range())
			s->piece_sizes2[std::size_t(static_cast<int>(p))] = fs.piece_size2(p);
	}
	std::vector<piece_cache::pad_ranges> pads(static_cast<std::size_t>(pieces));
	for (lt::file_index_t f : fs.file_range())
	{
		if (!fs.pad_file_at(f)) continue;
		std::int64_t offset = fs.file_offset(f);
		std::int64_t left = fs.file_size(f);
		while (left > 0)
		{
			int const piece = int(offset / piece_length);
			int const start = int(offset % piece_length);
			int const length = int(std::min<std::int64_t>(left, piece_length - start));
			pads[std::size_t(piece)].emplace_back(start, length);
			offset += length;
			left -= length;
		}
	}

	int const slots = cache_slots(m_config.cache_bytes, piece_length);
	if (slots < 2) throw std::runtime_error("Cache is too small for filesystem overhead and two pieces");
	std::string path;
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		path = m_config.directory + "/" + lt::aux::to_hex(params.info_hash) + "-" + std::to_string(m_next_file++)
			+ ".cache";
	}
	s->cache = std::make_shared<piece_cache>(path, piece_length, std::move(sizes), std::move(pads), slots);
	if (s->cache->get_stats().allocated_bytes > m_config.cache_bytes)
		throw std::runtime_error("Filesystem allocation exceeds the cache budget");
	s->cache->on_slot_freed([this] { schedule_retry(); });

	std::lock_guard<std::mutex> lock(m_mutex);
	lt::storage_index_t idx;
	if (m_free_storages.empty())
	{
		idx = m_storages.end_index();
		m_storages.push_back(std::move(s));
	}
	else
	{
		idx = m_free_storages.back();
		m_free_storages.pop_back();
		m_storages[idx] = std::move(s);
	}
	return lt::storage_holder(idx, *this);
}

void cache_disk_io::remove_torrent(lt::storage_index_t const idx)
{
	std::shared_ptr<storage> s;
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		s = std::move(m_storages[idx]);
		m_free_storages.push_back(idx);
	}
	// Readers holding the cache see it closed; its file goes with the last reference.
	if (!s) return;
	s->cache->on_slot_freed(nullptr);
	s->cache->close();
	// Hashes waiting for writes that will never come finish with an error, and so do the blocked writes.
	std::vector<std::function<void()>> orphans;
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		for (auto i = m_parked.begin(); i != m_parked.end();)
		{
			if (i->cache == s->cache) { orphans.push_back(std::move(i->job)); i = m_parked.erase(i); }
			else ++i;
		}
		if (!m_blocked.empty() && !m_retry_scheduled)
		{
			m_retry_scheduled = true;
			m_disk_jobs.push_back([this] { retry_blocked(); });
		}
	}
	for (auto& job : orphans) push_hash(std::move(job));
	m_disk_cv.notify_one();
}

char* cache_disk_io::allocate(int const size)
{
	char* const raw = new char[header + std::size_t(size)];
	std::memcpy(raw, &size, sizeof(size));
	std::lock_guard<std::mutex> lock(m_mutex);
	m_buffer_bytes += size;
	return raw + header;
}

void cache_disk_io::free_disk_buffer(char* const b)
{
	if (!b) return;
	char* const raw = b - header;
	int size;
	std::memcpy(&size, raw, sizeof(size));
	delete[] raw;
	std::lock_guard<std::mutex> lock(m_mutex);
	m_buffer_bytes -= size;
}

void cache_disk_io::free_multiple_buffers(lt::span<char*> const bufs)
{
	for (char* b : bufs) free_disk_buffer(b);
}

void cache_disk_io::async_read(lt::storage_index_t const storage_idx, lt::peer_request const& r
	, std::function<void(lt::disk_buffer_holder, lt::storage_error const&)> handler, lt::disk_job_flags_t)
{
	auto s = get(storage_idx);
	push_disk([this, s, r, handler = std::move(handler)] {
		lt::storage_error error;
		char* buf = nullptr;
		if (!s) error = aborted(lt::operation_t::file_read);
		else
		{
			buf = allocate(r.length);
			s->cache->read(r.piece, r.start, {buf, r.length}, error);
			if (error) { free_disk_buffer(buf); buf = nullptr; }
		}
		boost::asio::post(m_ioc, [this, handler, buf, error] {
			handler(buf ? lt::disk_buffer_holder(*this, buf) : lt::disk_buffer_holder(), error);
		});
	});
}

bool cache_disk_io::async_write(lt::storage_index_t const storage_idx, lt::peer_request const& r, char const* buf
	, std::shared_ptr<lt::disk_observer> o, std::function<void(lt::storage_error const&)> handler
	, lt::disk_job_flags_t)
{
	auto s = get(storage_idx);
	std::shared_ptr<char[]> data(new char[std::size_t(r.length)]);
	std::memcpy(data.get(), buf, std::size_t(r.length));
	bool exceeded;
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		m_queued_write_bytes += r.length;
		m_buffer_bytes += r.length;
		m_disk_jobs.push_back([this, s, r, data, handler = std::move(handler)] {
			lt::storage_error error;
			if (!s) error = aborted(lt::operation_t::file_write);
			else if (!s->cache->write(r.piece, r.start, {data.get(), r.length}, error))
			{
				// No free slot: hold the block until eviction frees one.
				std::lock_guard<std::mutex> l(m_mutex);
				m_blocked.push_back({s->cache, r, data, handler});
				return;
			}
			finish_write(r.length, handler, error);
			if (s) hash_parked(s->cache.get(), r.piece);
		});
		exceeded = m_queued_write_bytes > m_config.write_queue_bytes || !m_blocked.empty();
		if (exceeded && o) m_observers.push_back(std::move(o));
	}
	m_disk_cv.notify_one();
	return exceeded;
}

void cache_disk_io::finish_write(int const length, std::function<void(lt::storage_error const&)> const& handler
	, lt::storage_error const& error)
{
	std::vector<std::shared_ptr<lt::disk_observer>> resume;
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		m_queued_write_bytes -= length;
		m_buffer_bytes -= length;
		// Peers resume once the queue is half drained and nothing waits for a slot.
		if (m_queued_write_bytes <= m_config.write_queue_bytes / 2 && m_blocked.empty()) resume.swap(m_observers);
	}
	boost::asio::post(m_ioc, [handler, error] { handler(error); });
	for (auto& o : resume) boost::asio::post(m_ioc, [o] { o->on_disk(); });
}

void cache_disk_io::schedule_retry()
{
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		if (m_blocked.empty() || m_retry_scheduled) return;
		m_retry_scheduled = true;
		m_disk_jobs.push_back([this] { retry_blocked(); });
	}
	m_disk_cv.notify_one();
}

void cache_disk_io::retry_blocked()
{
	std::deque<blocked_write> blocked;
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		m_retry_scheduled = false;
		blocked.swap(m_blocked);
	}
	// Each torrent has its own slots, so one still waiting doesn't hold up another.
	std::deque<blocked_write> waiting;
	for (auto& w : blocked)
	{
		lt::storage_error error;
		if (!w.cache->write(w.request.piece, w.request.start, {w.data.get(), w.request.length}, error))
		{
			waiting.push_back(std::move(w));
			continue;
		}
		finish_write(w.request.length, w.handler, error);
		hash_parked(w.cache.get(), w.request.piece);
	}
	if (waiting.empty()) return;
	std::lock_guard<std::mutex> lock(m_mutex);
	// Ahead of any that blocked meanwhile, to keep their order.
	for (auto i = waiting.rbegin(); i != waiting.rend(); ++i) m_blocked.push_front(std::move(*i));
}

void cache_disk_io::hash_when_written(std::shared_ptr<piece_cache> const& cache, piece_index_t const piece
	, int const offset, std::function<void()> job)
{
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		bool const ready = m_abort || !cache || (offset < 0 ? cache->complete(piece) : cache->block_written(piece, offset));
		if (!ready)
		{
			m_parked.push_back({cache, piece, offset, std::move(job)});
			return;
		}
	}
	push_hash(std::move(job));
}

void cache_disk_io::hash_parked(piece_cache const* const cache, piece_index_t const piece)
{
	std::vector<std::function<void()>> ready;
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		for (auto i = m_parked.begin(); i != m_parked.end();)
		{
			if (i->cache.get() == cache && i->piece == piece
				&& (i->offset < 0 ? cache->complete(piece) : cache->block_written(piece, i->offset)))
			{
				ready.push_back(std::move(i->job));
				i = m_parked.erase(i);
			}
			else ++i;
		}
	}
	for (auto& job : ready) push_hash(std::move(job));
}

void cache_disk_io::release(std::shared_ptr<piece_cache> cache, std::vector<piece_index_t> pieces)
{
	push_disk([cache = std::move(cache), pieces = std::move(pieces)] {
		for (piece_index_t const p : pieces) cache->release(p);
	});
}

void cache_disk_io::async_hash(lt::storage_index_t const storage_idx, lt::piece_index_t const piece
	, lt::span<lt::sha256_hash> const v2, lt::disk_job_flags_t const flags
	, std::function<void(lt::piece_index_t, lt::sha1_hash const&, lt::storage_error const&)> handler)
{
	auto s = get(storage_idx);
	// libtorrent asks once the last block is queued for writing, not written; the disk worker reaches this
	// after those writes and hands the hash over once the piece is complete. An unverified piece is never freed.
	std::function<void()> job = [this, s, piece, v2, flags, handler = std::move(handler)] {
		lt::storage_error error;
		lt::sha1_hash result;
		if (!s) error = aborted(lt::operation_t::file_read);
		else
		{
			bool const v1 = bool(flags & lt::disk_interface::v1_hash);
			int const size = s->cache->piece_size(piece);
			int const size2 = s->piece_sizes2.empty() ? 0 : s->piece_sizes2[std::size_t(static_cast<int>(piece))];
			lt::hasher h1;
			char block[lt::default_block_size];
			for (int offset = 0, b = 0; offset < size && !error; offset += lt::default_block_size, ++b)
			{
				int const length = std::min(lt::default_block_size, size - offset);
				s->cache->read(piece, offset, {block, length}, error);
				if (error) break;
				if (v1) h1.update({block, length});
				if (!v2.empty() && offset < size2)
				{
					int const length2 = std::min(lt::default_block_size, size2 - offset);
					v2[b] = lt::hasher256(lt::span<char const>(block, length2)).final();
				}
			}
			if (v1 && !error) result = h1.final();
		}
		boost::asio::post(m_ioc, [handler, piece, result, error] { handler(piece, result, error); });
	};
	push_disk([this, s, piece, job = std::move(job)]() mutable {
		hash_when_written(s ? s->cache : nullptr, piece, -1, std::move(job));
	});
}

void cache_disk_io::async_hash2(lt::storage_index_t const storage_idx, lt::piece_index_t const piece
	, int const offset, lt::disk_job_flags_t
	, std::function<void(lt::piece_index_t, lt::sha256_hash const&, lt::storage_error const&)> handler)
{
	auto s = get(storage_idx);
	std::function<void()> job = [this, s, piece, offset, handler = std::move(handler)] {
		lt::storage_error error;
		lt::sha256_hash result;
		if (!s || s->piece_sizes2.empty()) error = aborted(lt::operation_t::file_read);
		else
		{
			int const size2 = s->piece_sizes2[std::size_t(static_cast<int>(piece))];
			int const length = std::min(lt::default_block_size, size2 - offset);
			char block[lt::default_block_size];
			s->cache->read(piece, offset, {block, length}, error);
			if (!error) result = lt::hasher256(lt::span<char const>(block, length)).final();
		}
		boost::asio::post(m_ioc, [handler, piece, result, error] { handler(piece, result, error); });
	};
	push_disk([this, s, piece, offset, job = std::move(job)]() mutable {
		hash_when_written(s ? s->cache : nullptr, piece, offset, std::move(job));
	});
}

void cache_disk_io::async_clear_piece(lt::storage_index_t const storage_idx, lt::piece_index_t const index
	, std::function<void(lt::piece_index_t)> handler)
{
	auto s = get(storage_idx);
	push_disk([this, s, index, handler = std::move(handler)] {
		if (s) s->cache->clear(index);
		boost::asio::post(m_ioc, [handler, index] { handler(index); });
	});
}

void cache_disk_io::async_move_storage(lt::storage_index_t, std::string p, lt::move_flags_t
	, std::function<void(lt::status_t, std::string const&, lt::storage_error const&)> handler)
{
	lt::storage_error error;
	error.ec = boost::system::errc::make_error_code(boost::system::errc::operation_not_supported);
	error.operation = lt::operation_t::file_rename;
	boost::asio::post(m_ioc, [handler, p, error] { handler(lt::disk_status::fatal_disk_error, p, error); });
}

void cache_disk_io::async_release_files(lt::storage_index_t, std::function<void()> handler)
{
	if (handler) boost::asio::post(m_ioc, handler);
}

void cache_disk_io::async_check_files(lt::storage_index_t, lt::add_torrent_params const*
	, lt::aux::vector<std::string, lt::file_index_t>
	, std::function<void(lt::status_t, lt::storage_error const&)> handler)
{
	// Every torrent starts with an empty cache: nothing to check, and no pieces to trust.
	boost::asio::post(m_ioc, [handler] { handler(lt::status_t{}, lt::storage_error()); });
}

void cache_disk_io::async_stop_torrent(lt::storage_index_t, std::function<void()> handler)
{
	if (handler) boost::asio::post(m_ioc, handler);
}

void cache_disk_io::async_rename_file(lt::storage_index_t, lt::file_index_t const index, std::string name
	, std::function<void(std::string const&, lt::file_index_t, lt::storage_error const&)> handler)
{
	// Cache slots aren't named after files.
	boost::asio::post(m_ioc, [handler, name, index] { handler(name, index, lt::storage_error()); });
}

void cache_disk_io::async_delete_files(lt::storage_index_t, lt::remove_flags_t
	, std::function<void(lt::storage_error const&)> handler)
{
	// The cache file goes with the torrent's storage.
	boost::asio::post(m_ioc, [handler] { handler(lt::storage_error()); });
}

void cache_disk_io::async_set_file_priority(lt::storage_index_t
	, lt::aux::vector<lt::download_priority_t, lt::file_index_t> prio
	, std::function<void(lt::storage_error const&, lt::aux::vector<lt::download_priority_t, lt::file_index_t>)> handler)
{
	// No part files: priorities only change what the torrent downloads.
	boost::asio::post(m_ioc, [handler, prio = std::move(prio)]() mutable { handler(lt::storage_error(), std::move(prio)); });
}

void cache_disk_io::update_stats_counters(lt::counters&) const {}

std::vector<lt::open_file_state> cache_disk_io::get_status(lt::storage_index_t) const
{
	return {};
}

cache_disk_io::stats cache_disk_io::get_stats() const
{
	std::lock_guard<std::mutex> lock(m_mutex);
	stats ret;
	ret.buffer_bytes = m_buffer_bytes;
	ret.queued_write_bytes = m_queued_write_bytes;
	ret.blocked_writes = int(m_blocked.size());
	return ret;
}

void cache_disk_io::settings_updated() {}

void cache_disk_io::abort(bool const wait)
{
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		if (!m_abort)
		{
			m_abort = true;
			for (auto const& s : m_storages) if (s) s->cache->close();
			// Complete cancelled jobs while the session's io_context still owns live torrent state.
			for (auto& w : m_blocked)
				m_disk_jobs.push_back([this, length = w.request.length, handler = std::move(w.handler)] {
					finish_write(length, handler, aborted(lt::operation_t::file_write));
				});
			m_blocked.clear();
			for (auto& p : m_parked) m_hash_jobs.push_back(std::move(p.job));
			m_parked.clear();
		}
	}
	m_disk_cv.notify_all();
	m_hash_cv.notify_all();
	if (!wait) return;
	if (m_disk_thread.joinable()) m_disk_thread.join();
	if (m_hash_thread.joinable()) m_hash_thread.join();
}

lt::disk_io_constructor_type cache_disk_io_constructor(cache_config cfg, std::function<void(cache_disk_io*)> created)
{
	return [cfg = std::move(cfg), created = std::move(created)](lt::io_context& ioc, lt::settings_interface const&
		, lt::counters&) {
		auto io = std::make_unique<cache_disk_io>(ioc, cfg);
		if (created) created(io.get());
		return std::unique_ptr<lt::disk_interface>(std::move(io));
	};
}

} // namespace dtv
