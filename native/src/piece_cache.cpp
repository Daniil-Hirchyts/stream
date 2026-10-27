#include "piece_cache.hpp"

#include <libtorrent/disk_interface.hpp> // default_block_size
#include <libtorrent/operations.hpp>

#include <boost/asio/error.hpp>

#include <algorithm>
#include <cerrno>
#include <fcntl.h>
#include <sys/stat.h>
#include <system_error>
#include <unistd.h>

namespace dtv {

namespace {

lt::error_code last_error() { return {errno, lt::system_category()}; }

// pread/pwrite may transfer less than asked; EINTR is retried. A slot may extend past the end of the file, where
// nothing was written yet: that reads as zeros, like a hole.
bool full_pread(int fd, char* out, std::size_t size, std::int64_t offset)
{
	while (size > 0)
	{
		ssize_t const n = ::pread(fd, out, size, off_t(offset));
		if (n < 0 && errno == EINTR) continue;
		if (n < 0) return false;
		if (n == 0) { std::fill(out, out + size, '\0'); return true; }
		out += n;
		size -= std::size_t(n);
		offset += n;
	}
	return true;
}

bool full_pwrite(int fd, char const* data, std::size_t size, std::int64_t offset)
{
	while (size > 0)
	{
		ssize_t const n = ::pwrite(fd, data, size, off_t(offset));
		if (n < 0 && errno == EINTR) continue;
		if (n <= 0) { if (n == 0) errno = EIO; return false; }
		data += n;
		size -= std::size_t(n);
		offset += n;
	}
	return true;
}

} // namespace

namespace {

// Reserve before accepting payload writes. A failed reservation must not silently become sparse storage.
void preallocate_file(int fd, std::int64_t size)
{
#if defined __APPLE__
	fstore_t store{};
	store.fst_flags = F_ALLOCATEALL;
	store.fst_posmode = F_PEOFPOSMODE;
	store.fst_length = off_t(size);
	if (::fcntl(fd, F_PREALLOCATE, &store) != 0)
		throw std::system_error(errno, std::generic_category(), "preallocate cache");
#elif defined __linux__
	if (::fallocate(fd, 0, 0, off_t(size)) != 0)
		throw std::system_error(errno, std::generic_category(), "preallocate cache");
#endif
	if (::ftruncate(fd, off_t(size)) != 0)
		throw std::system_error(errno, std::generic_category(), "size cache");
}

} // namespace

piece_cache::piece_cache(std::string path, int const piece_length, std::vector<int> piece_sizes
	, std::vector<pad_ranges> pads, int const slots, bool const preallocate)
	: m_path(std::move(path))
	, m_piece_length(piece_length)
	, m_piece_sizes(std::move(piece_sizes))
	, m_pads(std::move(pads))
	, m_slots(std::size_t(slots))
	, m_slot_of(m_piece_sizes.size(), -1)
{
	m_fd = ::open(m_path.c_str(), O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
	if (m_fd < 0) throw std::system_error(errno, std::generic_category(), "open " + m_path);
	try
	{
		if (preallocate) preallocate_file(m_fd, std::int64_t(slots) * piece_length);
	}
	catch (...)
	{
		::close(m_fd);
		::unlink(m_path.c_str());
		throw;
	}
	// Lower slots first, so a sparse file stays as short as the pieces in use allow.
	for (int s = slots - 1; s >= 0; --s) m_free.push_back(s);
}

piece_cache::~piece_cache()
{
	if (m_fd >= 0) ::close(m_fd);
	::unlink(m_path.c_str());
}

int piece_cache::blocks_in(piece_index_t const p) const
{
	return (piece_size(p) + lt::default_block_size - 1) / lt::default_block_size;
}

int piece_cache::required_blocks(piece_index_t const p) const
{
	auto const& pads = m_pads[std::size_t(static_cast<int>(p))];
	if (pads.empty()) return blocks_in(p);
	int count = 0;
	for (int b = 0; b < blocks_in(p); ++b)
	{
		int const start = b * lt::default_block_size;
		int const end = std::min(start + lt::default_block_size, piece_size(p));
		bool all_pad = false;
		for (auto const& [offset, length] : pads)
			if (offset <= start && end <= offset + length) all_pad = true;
		if (!all_pad) ++count;
	}
	return count;
}

void piece_cache::zero_pads(piece_index_t const p, int const offset, lt::span<char> const out) const
{
	for (auto const& [pad_offset, pad_length] : m_pads[std::size_t(static_cast<int>(p))])
	{
		int const start = std::max(offset, pad_offset);
		int const end = std::min(offset + int(out.size()), pad_offset + pad_length);
		if (start < end) std::fill(out.data() + (start - offset), out.data() + (end - offset), '\0');
	}
}

int piece_cache::take_slot_locked(piece_index_t const p)
{
	if (!valid(p) || m_free.empty() || m_closed) return -1;
	int const s = m_free.back();
	m_free.pop_back();
	slot& sl = m_slots[std::size_t(s)];
	sl.piece = p;
	sl.st = state::reserved;
	sl.releasing = false;
	sl.release_requested = false;
	sl.pins = 0;
	sl.written_blocks = 0;
	sl.required = required_blocks(p);
	sl.written.assign(std::size_t(blocks_in(p)), false);
	// A piece made only of padding has nothing to download.
	if (sl.required == 0) sl.st = state::complete;
	sl.last_use = ++m_tick;
	m_slot_of[std::size_t(static_cast<int>(p))] = s;
	return s;
}

bool piece_cache::reserve(piece_index_t const p)
{
	std::lock_guard<std::mutex> lock(m_mutex);
	int const s = slot_of(p);
	if (s >= 0) return !m_slots[std::size_t(s)].releasing;
	return take_slot_locked(p) >= 0;
}

void piece_cache::unreserve(piece_index_t const p)
{
	// Copied under the lock: remove_torrent() may clear it concurrently.
	std::function<void()> freed;
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		int const s = slot_of(p);
		if (s < 0) return;
		slot& sl = m_slots[std::size_t(s)];
		if (sl.st != state::reserved || sl.pins > 0) return;
		sl.st = state::free;
		sl.piece = piece_index_t{-1};
		m_slot_of[std::size_t(static_cast<int>(p))] = -1;
		m_free.push_back(s);
		freed = m_on_slot_freed;
	}
	if (freed) freed();
}

bool piece_cache::has_slot(piece_index_t const p) const
{
	std::lock_guard<std::mutex> lock(m_mutex);
	return slot_of(p) >= 0;
}

bool piece_cache::has_data(piece_index_t const p) const
{
	std::lock_guard<std::mutex> lock(m_mutex);
	int const s = slot_of(p);
	return s >= 0 && m_slots[std::size_t(s)].st != state::reserved;
}

bool piece_cache::complete(piece_index_t const p) const
{
	std::lock_guard<std::mutex> lock(m_mutex);
	int const s = slot_of(p);
	return s >= 0 && (m_slots[std::size_t(s)].st == state::complete || m_slots[std::size_t(s)].st == state::verified);
}

bool piece_cache::block_written(piece_index_t const p, int const offset) const
{
	std::lock_guard<std::mutex> lock(m_mutex);
	int const s = slot_of(p);
	if (s < 0) return false;
	slot const& sl = m_slots[std::size_t(s)];
	if (sl.st == state::complete || sl.st == state::verified) return true;
	std::size_t const b = std::size_t(offset / lt::default_block_size);
	return b < sl.written.size() && sl.written[b];
}

int piece_cache::free_slots() const
{
	std::lock_guard<std::mutex> lock(m_mutex);
	return int(m_free.size());
}

bool piece_cache::write(piece_index_t const p, int const offset, lt::span<char const> const data
	, lt::storage_error& error)
{
	int s;
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		if (m_closed)
		{
			error.ec = boost::asio::error::operation_aborted;
			error.operation = lt::operation_t::file_write;
			return true;
		}
		if (!valid(p) || offset < 0 || offset + int(data.size()) > piece_size(p))
		{
			error.ec = boost::asio::error::invalid_argument;
			error.operation = lt::operation_t::file_write;
			return true;
		}
		s = slot_of(p);
		// A slot being released belongs to the evicted copy; the new one waits for a fresh slot.
		if (s >= 0 && m_slots[std::size_t(s)].releasing) return false;
		if (s < 0) s = take_slot_locked(p);
		if (s < 0) return false;
		++m_slots[std::size_t(s)].pins;
	}
	bool const ok = full_pwrite(m_fd, data.data(), std::size_t(data.size()), slot_offset(s) + offset);
	if (!ok)
	{
		error.ec = last_error();
		error.operation = lt::operation_t::file_write;
	}
	else
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		slot& sl = m_slots[std::size_t(s)];
		int const first = offset / lt::default_block_size;
		int const last = (offset + int(data.size()) + lt::default_block_size - 1) / lt::default_block_size;
		for (int b = first; b < last && b < int(sl.written.size()); ++b)
		{
			if (sl.written[std::size_t(b)]) continue;
			sl.written[std::size_t(b)] = true;
			++sl.written_blocks;
		}
		if (sl.st == state::reserved) sl.st = state::partial;
		if (sl.st == state::partial && sl.written_blocks >= sl.required) sl.st = state::complete;
		sl.last_use = ++m_tick;
	}
	unpin(s);
	return true;
}

void piece_cache::read(piece_index_t const p, int const offset, lt::span<char> const out, lt::storage_error& error)
{
	int s;
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		s = slot_of(p);
		if (s < 0 || (m_slots[std::size_t(s)].st != state::complete && m_slots[std::size_t(s)].st != state::verified)
			|| offset < 0 || offset + int(out.size()) > piece_size(p))
		{
			// libtorrent only reads pieces it has, which are never freed before it forgets them.
			error.ec = boost::asio::error::eof;
			error.operation = lt::operation_t::file_read;
			return;
		}
		++m_slots[std::size_t(s)].pins;
	}
	if (!full_pread(m_fd, out.data(), std::size_t(out.size()), slot_offset(s) + offset))
	{
		error.ec = last_error();
		error.operation = lt::operation_t::file_read;
	}
	else zero_pads(p, offset, out);
	unpin(s);
}

void piece_cache::clear(piece_index_t const p)
{
	std::lock_guard<std::mutex> lock(m_mutex);
	int const s = slot_of(p);
	if (s < 0) return;
	slot& sl = m_slots[std::size_t(s)];
	sl.written.assign(sl.written.size(), false);
	sl.written_blocks = 0;
	if (sl.st != state::free) sl.st = state::reserved;
}

void piece_cache::release(piece_index_t const p)
{
	int s;
	bool detached;
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		s = slot_of(p);
		if (s < 0) return;
		slot& sl = m_slots[std::size_t(s)];
		sl.releasing = true;
		sl.release_requested = true;
		detached = maybe_free_locked(s);
	}
	if (detached)
	{
		std::function<void()> freed;
		{
			std::lock_guard<std::mutex> lock(m_mutex);
			m_free.push_back(s);
			freed = m_on_slot_freed;
		}
		if (freed) freed();
	}
}

bool piece_cache::maybe_free_locked(int const s)
{
	slot& sl = m_slots[std::size_t(s)];
	if (!sl.release_requested || sl.pins > 0) return false;
	if (sl.st == state::verified) ++m_evicted;
	m_slot_of[std::size_t(static_cast<int>(sl.piece))] = -1;
	sl.piece = piece_index_t{-1};
	sl.st = state::free;
	sl.releasing = false;
	sl.release_requested = false;
	sl.written.clear();
	sl.written_blocks = 0;
	// The slot is reused in place: the file never holds more than its slots, and no write can grow it beyond them.
	return true;
}

void piece_cache::unpin(int const s)
{
	bool detached;
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		--m_slots[std::size_t(s)].pins;
		detached = maybe_free_locked(s);
	}
	if (detached)
	{
		std::function<void()> freed;
		{
			std::lock_guard<std::mutex> lock(m_mutex);
			m_free.push_back(s);
			freed = m_on_slot_freed;
		}
		if (freed) freed();
	}
}

void piece_cache::set_verified(piece_index_t const p)
{
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		int const s = slot_of(p);
		if (s < 0) return;
		slot& sl = m_slots[std::size_t(s)];
		if (sl.st != state::complete) return;
		sl.st = state::verified;
		sl.last_use = ++m_tick;
	}
	m_verified_cv.notify_all();
}

bool piece_cache::verified(piece_index_t const p) const
{
	std::lock_guard<std::mutex> lock(m_mutex);
	int const s = slot_of(p);
	return s >= 0 && m_slots[std::size_t(s)].st == state::verified && !m_slots[std::size_t(s)].releasing;
}

piece_cache::wait_result piece_cache::wait_verified(piece_index_t const p
	, std::chrono::steady_clock::time_point const deadline, std::atomic<bool> const& cancelled)
{
	std::unique_lock<std::mutex> lock(m_mutex);
	for (;;)
	{
		if (m_closed) return wait_result::closed;
		if (cancelled.load()) return wait_result::cancelled;
		int const s = slot_of(p);
		if (s >= 0 && m_slots[std::size_t(s)].st == state::verified && !m_slots[std::size_t(s)].releasing)
			return wait_result::ready;
		if (m_verified_cv.wait_until(lock, deadline) == std::cv_status::timeout
			&& std::chrono::steady_clock::now() >= deadline)
			return wait_result::timeout;
	}
}

bool piece_cache::read_verified(piece_index_t const p, int const offset, lt::span<char> const out
	, lt::storage_error& error)
{
	int s;
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		s = slot_of(p);
		if (s < 0 || m_slots[std::size_t(s)].st != state::verified || m_slots[std::size_t(s)].releasing
			|| offset < 0 || offset + int(out.size()) > piece_size(p))
			return false;
		slot& sl = m_slots[std::size_t(s)];
		++sl.pins;
		sl.last_use = ++m_tick;
	}
	if (!full_pread(m_fd, out.data(), std::size_t(out.size()), slot_offset(s) + offset))
	{
		error.ec = last_error();
		error.operation = lt::operation_t::file_read;
	}
	else zero_pads(p, offset, out);
	unpin(s);
	return true;
}

void piece_cache::wake_all()
{
	// Taking the lock orders the wake-up after any waiter's check of its flags.
	{ std::lock_guard<std::mutex> lock(m_mutex); }
	m_verified_cv.notify_all();
}

void piece_cache::close()
{
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		m_closed = true;
	}
	m_verified_cv.notify_all();
}

std::vector<piece_index_t> piece_cache::eviction_candidates(int const count
	, std::function<bool(piece_index_t)> const& is_protected) const
{
	std::vector<std::pair<std::uint64_t, piece_index_t>> found;
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		for (slot const& sl : m_slots)
		{
			if (sl.st != state::verified || sl.pins > 0 || sl.releasing) continue;
			if (is_protected && is_protected(sl.piece)) continue;
			found.emplace_back(sl.last_use, sl.piece);
		}
	}
	std::sort(found.begin(), found.end());
	std::vector<piece_index_t> ret;
	for (std::size_t i = 0; i < found.size() && int(ret.size()) < count; ++i) ret.push_back(found[i].second);
	return ret;
}

void piece_cache::begin_release(std::vector<piece_index_t> const& pieces)
{
	std::lock_guard<std::mutex> lock(m_mutex);
	for (piece_index_t const p : pieces)
	{
		int const s = slot_of(p);
		if (s >= 0) m_slots[std::size_t(s)].releasing = true;
	}
}

void piece_cache::cancel_release(piece_index_t const p)
{
	std::lock_guard<std::mutex> lock(m_mutex);
	int const s = slot_of(p);
	if (s >= 0 && !m_slots[std::size_t(s)].release_requested) m_slots[std::size_t(s)].releasing = false;
}

std::vector<std::pair<piece_index_t, piece_cache::kind>> piece_cache::pieces() const
{
	std::vector<std::pair<piece_index_t, kind>> ret;
	std::lock_guard<std::mutex> lock(m_mutex);
	for (slot const& sl : m_slots)
	{
		switch (sl.st)
		{
			case state::free: break;
			case state::reserved: ret.emplace_back(sl.piece, kind::reserved); break;
			case state::partial: ret.emplace_back(sl.piece, kind::partial); break;
			case state::complete: ret.emplace_back(sl.piece, kind::complete); break;
			case state::verified: ret.emplace_back(sl.piece, kind::verified); break;
		}
	}
	return ret;
}

void piece_cache::on_slot_freed(std::function<void()> fn)
{
	std::lock_guard<std::mutex> lock(m_mutex);
	m_on_slot_freed = std::move(fn);
}

piece_cache::stats piece_cache::get_stats() const
{
	stats ret;
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		ret.slots = int(m_slots.size());
		for (slot const& sl : m_slots)
		{
			if (sl.st == state::free) continue;
			++ret.used;
			if (sl.st == state::verified) ++ret.verified;
			if (sl.pins > 0) ++ret.pinned;
		}
		ret.evicted = m_evicted;
	}
	struct stat st{};
	if (::fstat(m_fd, &st) == 0)
	{
		ret.file_bytes = std::int64_t(st.st_size);
		ret.allocated_bytes = std::int64_t(st.st_blocks) * 512;
	}
	return ret;
}

} // namespace dtv
