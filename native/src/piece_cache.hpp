#pragma once

#include "stream_core.h"

#include <libtorrent/error_code.hpp>
#include <libtorrent/span.hpp>
#include <libtorrent/units.hpp>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace dtv {

using lt::piece_index_t;

// A bounded store for one torrent's pieces: a single preallocated file divided into slots of one piece each, never
// more slots than the cache budget allows. A piece moves from reserved (a slot, no data) to partial, complete (every block
// written) and verified (the torrent passed its hash). Disk jobs and readers pin a slot while they use it; a slot is
// freed only once its piece is released and unpinned, and the next piece overwrites it in place. The file is deleted
// with the cache, so it never occupies more than the slots' storage.
// All members are thread-safe; file I/O runs outside the lock.
class piece_cache
{
public:
	struct stats
	{
		int slots = 0;
		int used = 0;
		int verified = 0;
		int pinned = 0;
		std::int64_t evicted = 0;
		// The cache file's length, never more than slots times the piece length.
		std::int64_t file_bytes = 0;
		// Bytes the file occupies on disk. Holes don't count; filesystem allocation may add a few blocks.
		std::int64_t allocated_bytes = 0;
	};

	enum class wait_result { ready, timeout, cancelled, closed };

	// Byte ranges of a piece that belong to pad files (BEP 47). libtorrent never writes them; they read as zeros.
	using pad_ranges = std::vector<std::pair<int, int>>;

	// piece_sizes holds the size of every piece (the last is usually short), pads the pad ranges of every piece.
	// With `preallocate`, the file gets its full size and storage at once: it never grows, so filesystems can't
	// add speculative blocks, and a full disk shows at the start rather than during playback.
	piece_cache(std::string path, int piece_length, std::vector<int> piece_sizes, std::vector<pad_ranges> pads
		, int slots, bool preallocate = true);
	~piece_cache();
	piece_cache(piece_cache const&) = delete;
	piece_cache& operator=(piece_cache const&) = delete;

	int piece_length() const { return stream_cache_piece_length(m_cache); }
	int num_pieces() const { return stream_cache_num_pieces(m_cache); }
	// 0 for an index outside the torrent.
	int piece_size(piece_index_t p) const { return stream_cache_piece_size(m_cache, static_cast<int>(p)); }
	int slot_count() const { return stream_cache_slot_count(m_cache); }
	// The Rust cache this object holds a reference to.
	stream_cache const* handle() const { return m_cache; }

	// Gives the piece a slot ahead of its data; false when none is free, or while its previous slot is still being
	// released. Reserving a piece twice is harmless.
	bool reserve(piece_index_t p);
	// Frees a reserved slot no data reached, such as a piece that left the read-ahead window.
	void unreserve(piece_index_t p);
	bool has_slot(piece_index_t p) const;
	bool has_data(piece_index_t p) const;
	// Every block holding data is written (complete or verified), or just the block at `offset`.
	bool complete(piece_index_t p) const;
	bool block_written(piece_index_t p, int offset) const;
	int free_slots() const;

	// Disk-thread operations. write() takes a slot if the piece has none; it returns false, without error, when
	// none is free, or the piece's slot is still being released, so the caller can hold the block until then.
	bool write(piece_index_t p, int offset, lt::span<char const> data, lt::storage_error& error);
	// Reads written data of a complete or verified piece.
	void read(piece_index_t p, int offset, lt::span<char> out, lt::storage_error& error);
	// The hash failed: forget the blocks so they are written again, keeping the slot.
	void clear(piece_index_t p);
	// Frees the piece's slot as soon as nothing pins it. Readers stopped pinning it at begin_release().
	void release(piece_index_t p);

	void set_verified(piece_index_t p);
	bool verified(piece_index_t p) const;

	// Readers: waits until the piece is verified, the deadline passes, or the reader is cancelled or the cache
	// closed. read_verified() returns false when the piece is not verified (anymore).
	wait_result wait_verified(piece_index_t p, std::chrono::steady_clock::time_point deadline
		, std::atomic<bool> const& cancelled);
	bool read_verified(piece_index_t p, int offset, lt::span<char> out, lt::storage_error& error);
	// Wakes waiting readers, for example after a reader was cancelled or the torrent failed.
	void wake_all();
	void close();

	// Eviction: verified pieces nothing pins, least recently used first, skipping protected ones.
	std::vector<piece_index_t> eviction_candidates(int count
		, std::function<bool(piece_index_t)> const& is_protected) const;
	// Stops new reader pins before the torrent forgets the pieces; cancel_release() undoes it for a piece the
	// torrent did not evict.
	void begin_release(std::vector<piece_index_t> const& pieces);
	void cancel_release(piece_index_t p);

	// Every piece holding a slot, and how far it got.
	enum class kind { reserved, partial, complete, verified };
	std::vector<std::pair<piece_index_t, kind>> pieces() const;

	// Called whenever a slot becomes free, outside the lock.
	void on_slot_freed(std::function<void()> fn);

	stats get_stats() const;

private:
	stream_cache const* m_cache = nullptr;
};

} // namespace dtv
