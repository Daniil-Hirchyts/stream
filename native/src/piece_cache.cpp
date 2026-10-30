#include "piece_cache.hpp"

#include <libtorrent/operations.hpp>

#include <boost/asio/error.hpp>

#include <system_error>

namespace dtv {

namespace {

// The Rust cache reports outcomes as stream_io; libtorrent expects its own error codes.
void translate(stream_io const& io, lt::operation_t op, lt::storage_error& error)
{
	switch (io.kind)
	{
		case 0: return;
		case 1: error.ec = boost::asio::error::operation_aborted; break;
		case 2: error.ec = boost::asio::error::invalid_argument; break;
		case 3: error.ec = boost::asio::error::eof; break;
		default: error.ec = lt::error_code(io.os_error, lt::system_category()); break;
	}
	error.operation = op;
}

std::uint8_t const* bytes(char const* p) { return reinterpret_cast<std::uint8_t const*>(p); }
std::uint8_t* bytes(char* p) { return reinterpret_cast<std::uint8_t*>(p); }

} // namespace

piece_cache::piece_cache(std::string path, int const piece_length, std::vector<int> piece_sizes
	, std::vector<pad_ranges> pads, int const slots, bool const preallocate)
{
	std::vector<int> pad_pieces, pad_offsets, pad_lengths;
	for (std::size_t p = 0; p < pads.size(); ++p)
	{
		for (auto const& [offset, length] : pads[p])
		{
			pad_pieces.push_back(int(p));
			pad_offsets.push_back(offset);
			pad_lengths.push_back(length);
		}
	}
	int error_number = 0;
	char message[512] = {};
	m_cache = stream_cache_new(path.c_str(), piece_length, piece_sizes.data(), int(piece_sizes.size())
		, pad_pieces.data(), pad_offsets.data(), pad_lengths.data(), int(pad_pieces.size()), slots, preallocate
		, &error_number, message, sizeof(message));
	if (!m_cache) throw std::system_error(error_number, std::generic_category(), message);
}

piece_cache::~piece_cache()
{
	// The Rust cache deletes its file once the last reference goes.
	stream_cache_release(m_cache);
}

bool piece_cache::reserve(piece_index_t const p) { return stream_cache_reserve(m_cache, static_cast<int>(p)); }
void piece_cache::unreserve(piece_index_t const p) { stream_cache_unreserve(m_cache, static_cast<int>(p)); }
bool piece_cache::has_slot(piece_index_t const p) const { return stream_cache_has_slot(m_cache, static_cast<int>(p)); }
bool piece_cache::has_data(piece_index_t const p) const { return stream_cache_has_data(m_cache, static_cast<int>(p)); }
bool piece_cache::complete(piece_index_t const p) const { return stream_cache_complete(m_cache, static_cast<int>(p)); }

bool piece_cache::block_written(piece_index_t const p, int const offset) const
{
	return stream_cache_block_written(m_cache, static_cast<int>(p), offset);
}

int piece_cache::free_slots() const { return stream_cache_free_slots(m_cache); }

bool piece_cache::write(piece_index_t const p, int const offset, lt::span<char const> const data
	, lt::storage_error& error)
{
	stream_io io{};
	bool const accepted = stream_cache_write(m_cache, static_cast<int>(p), offset, bytes(data.data())
		, std::size_t(data.size()), &io);
	translate(io, lt::operation_t::file_write, error);
	return accepted;
}

void piece_cache::read(piece_index_t const p, int const offset, lt::span<char> const out, lt::storage_error& error)
{
	stream_io io{};
	stream_cache_read(m_cache, static_cast<int>(p), offset, bytes(out.data()), std::size_t(out.size()), &io);
	translate(io, lt::operation_t::file_read, error);
}

void piece_cache::clear(piece_index_t const p) { stream_cache_clear(m_cache, static_cast<int>(p)); }
void piece_cache::release(piece_index_t const p) { stream_cache_release_piece(m_cache, static_cast<int>(p)); }
void piece_cache::set_verified(piece_index_t const p) { stream_cache_set_verified(m_cache, static_cast<int>(p)); }
bool piece_cache::verified(piece_index_t const p) const { return stream_cache_verified(m_cache, static_cast<int>(p)); }

piece_cache::wait_result piece_cache::wait_verified(piece_index_t const p
	, std::chrono::steady_clock::time_point const deadline, std::atomic<bool> const& cancelled)
{
	auto const left = std::chrono::duration_cast<std::chrono::nanoseconds>(deadline - std::chrono::steady_clock::now());
	// std::atomic<bool> and Rust's AtomicBool share a layout: one byte, accessed atomically.
	static_assert(sizeof(std::atomic<bool>) == 1, "std::atomic<bool> must be a single byte");
	switch (stream_cache_wait_verified(m_cache, static_cast<int>(p), std::max<std::int64_t>(0, left.count()), &cancelled))
	{
		case STREAM_WAIT_READY: return wait_result::ready;
		case STREAM_WAIT_CANCELLED: return wait_result::cancelled;
		case STREAM_WAIT_CLOSED: return wait_result::closed;
		default: return wait_result::timeout;
	}
}

bool piece_cache::read_verified(piece_index_t const p, int const offset, lt::span<char> const out
	, lt::storage_error& error)
{
	stream_io io{};
	bool const read = stream_cache_read_verified(m_cache, static_cast<int>(p), offset, bytes(out.data())
		, std::size_t(out.size()), &io);
	translate(io, lt::operation_t::file_read, error);
	return read;
}

void piece_cache::wake_all() { stream_cache_wake_all(m_cache); }
void piece_cache::close() { stream_cache_close(m_cache); }

std::vector<piece_index_t> piece_cache::eviction_candidates(int const count
	, std::function<bool(piece_index_t)> const& is_protected) const
{
	std::vector<int> found(std::size_t(std::max(0, slot_count())));
	auto const check = [](void* context, int p) {
		return (*static_cast<std::function<bool(piece_index_t)> const*>(context))(piece_index_t(p));
	};
	int const n = stream_cache_eviction_candidates(m_cache, count, is_protected ? +check : nullptr
		, const_cast<std::function<bool(piece_index_t)>*>(&is_protected), found.data(), int(found.size()));
	std::vector<piece_index_t> ret;
	for (int i = 0; i < n; ++i) ret.push_back(piece_index_t(found[std::size_t(i)]));
	return ret;
}

void piece_cache::begin_release(std::vector<piece_index_t> const& pieces)
{
	std::vector<int> raw;
	for (piece_index_t const p : pieces) raw.push_back(static_cast<int>(p));
	stream_cache_begin_release(m_cache, raw.data(), int(raw.size()));
}

void piece_cache::cancel_release(piece_index_t const p) { stream_cache_cancel_release(m_cache, static_cast<int>(p)); }

std::vector<std::pair<piece_index_t, piece_cache::kind>> piece_cache::pieces() const
{
	std::size_t const capacity = std::size_t(std::max(0, slot_count()));
	std::vector<int> pieces(capacity), kinds(capacity);
	int const n = stream_cache_pieces(m_cache, pieces.data(), kinds.data(), int(capacity));
	std::vector<std::pair<piece_index_t, kind>> ret;
	for (int i = 0; i < n; ++i) ret.emplace_back(piece_index_t(pieces[std::size_t(i)]), kind(kinds[std::size_t(i)]));
	return ret;
}

void piece_cache::on_slot_freed(std::function<void()> fn)
{
	if (!fn)
	{
		stream_cache_on_slot_freed(m_cache, nullptr, nullptr, nullptr);
		return;
	}
	// The Rust cache owns the copy and deletes it once no call can still use it.
	auto* owned = new std::function<void()>(std::move(fn));
	stream_cache_on_slot_freed(m_cache
		, [](void* context) { (*static_cast<std::function<void()>*>(context))(); }
		, [](void* context) { delete static_cast<std::function<void()>*>(context); }
		, owned);
}

piece_cache::stats piece_cache::get_stats() const
{
	stream_cache_stats s{};
	stream_cache_get_stats(m_cache, &s);
	stats ret;
	ret.slots = s.slots;
	ret.used = s.used;
	ret.verified = s.verified;
	ret.pinned = s.pinned;
	ret.evicted = s.evicted;
	ret.file_bytes = s.file_bytes;
	ret.allocated_bytes = s.allocated_bytes;
	return ret;
}

} // namespace dtv
