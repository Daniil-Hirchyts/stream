#include "test.hpp"

#include "fixtures.hpp"
#include "piece_cache.hpp"

#include <libtorrent/disk_interface.hpp>

#include <atomic>
#include <chrono>
#include <thread>
#include <vector>

using dtv::piece_cache;
using dtv::piece_index_t;
namespace fx = dtv::fixtures;

namespace {

constexpr int block = lt::default_block_size;

std::vector<char> pattern(int size, char seed)
{
	std::vector<char> v(static_cast<std::size_t>(size));
	for (int i = 0; i < size; ++i) v[std::size_t(i)] = char(seed + i * 7);
	return v;
}

void write_piece(piece_cache& c, piece_index_t p, char seed)
{
	auto const data = pattern(c.piece_size(p), seed);
	for (int offset = 0; offset < c.piece_size(p); offset += block)
	{
		lt::storage_error error;
		int const length = std::min(block, c.piece_size(p) - offset);
		CHECK(c.write(p, offset, {data.data() + offset, length}, error));
		CHECK(!error);
	}
}

} // namespace

TEST(piece_cache_piece_moves_from_reserved_to_verified)
{
	fx::temp_dir dir("dtv-cache");
	piece_cache c(dir.file("a.cache"), 4 * block, {4 * block, 4 * block, block}, {{}, {}, {}}, 2);
	CHECK(c.reserve(piece_index_t(0)));
	CHECK(c.has_slot(piece_index_t(0)));
	CHECK(!c.has_data(piece_index_t(0)));
	write_piece(c, piece_index_t(0), 3);
	CHECK(!c.verified(piece_index_t(0)));
	// Readers see only verified pieces.
	std::vector<char> out(static_cast<std::size_t>(block));
	lt::storage_error error;
	CHECK(!c.read_verified(piece_index_t(0), 0, {out.data(), block}, error));
	c.set_verified(piece_index_t(0));
	CHECK(c.verified(piece_index_t(0)));
	CHECK(c.read_verified(piece_index_t(0), block, {out.data(), block}, error));
	CHECK(!error);
	auto const expected = pattern(4 * block, 3);
	CHECK(std::equal(out.begin(), out.end(), expected.begin() + block));
}

TEST(piece_cache_never_exceeds_its_slots)
{
	fx::temp_dir dir("dtv-cache");
	piece_cache c(dir.file("a.cache"), 2 * block, std::vector<int>(10, 2 * block), std::vector<piece_cache::pad_ranges>(10), 3);
	CHECK(c.reserve(piece_index_t(0)));
	CHECK(c.reserve(piece_index_t(1)));
	CHECK(c.reserve(piece_index_t(1)));
	CHECK(c.reserve(piece_index_t(2)));
	CHECK(!c.reserve(piece_index_t(3)));
	CHECK_EQ(c.free_slots(), 0);
	// A write for a piece without a slot waits rather than overwriting another.
	auto const data = pattern(block, 1);
	lt::storage_error error;
	CHECK(!c.write(piece_index_t(4), 0, {data.data(), block}, error));
	CHECK(!error);
	c.unreserve(piece_index_t(2));
	CHECK(c.write(piece_index_t(4), 0, {data.data(), block}, error));
	CHECK(c.has_data(piece_index_t(4)));
	// A slot holding data is not unreserved.
	c.unreserve(piece_index_t(4));
	CHECK(c.has_slot(piece_index_t(4)));
}

TEST(piece_cache_release_frees_slots_without_growing_the_file)
{
	fx::temp_dir dir("dtv-cache");
	int const piece = 64 * block; // 1 MiB
	piece_cache c(dir.file("a.cache"), piece, std::vector<int>(8, piece), std::vector<piece_cache::pad_ranges>(8), 4);
	for (int p = 0; p < 4; ++p)
	{
		write_piece(c, piece_index_t(p), char(p));
		c.set_verified(piece_index_t(p));
	}
	auto const full = c.get_stats();
	CHECK_EQ(full.used, 4);
	CHECK(full.allocated_bytes >= 4 * piece);

	c.begin_release({piece_index_t(1)});
	// Readers stop at once, before the torrent has forgotten the piece.
	std::vector<char> out(static_cast<std::size_t>(block));
	lt::storage_error error;
	CHECK(!c.read_verified(piece_index_t(1), 0, {out.data(), block}, error));
	CHECK(!c.verified(piece_index_t(1)));
	c.release(piece_index_t(1));
	auto const after = c.get_stats();
	CHECK_EQ(after.used, 3);
	CHECK_EQ(after.evicted, 1);
	CHECK_EQ(c.free_slots(), 1);
	// The next piece reuses the slot in place: the file and its storage don't grow.
	write_piece(c, piece_index_t(6), 6);
	auto const reused = c.get_stats();
	CHECK_EQ(reused.file_bytes, full.file_bytes);
	CHECK(reused.allocated_bytes <= full.allocated_bytes);

	// A cancelled release keeps the piece readable.
	c.begin_release({piece_index_t(2)});
	c.cancel_release(piece_index_t(2));
	CHECK(c.read_verified(piece_index_t(2), 0, {out.data(), block}, error));
}

TEST(piece_cache_eviction_candidates_are_least_recently_used_and_unprotected)
{
	fx::temp_dir dir("dtv-cache");
	piece_cache c(dir.file("a.cache"), block, std::vector<int>(6, block), std::vector<piece_cache::pad_ranges>(6), 6);
	for (int p = 0; p < 5; ++p)
	{
		write_piece(c, piece_index_t(p), char(p));
		c.set_verified(piece_index_t(p));
	}
	// Reading piece 0 makes it the most recent.
	std::vector<char> out(static_cast<std::size_t>(block));
	lt::storage_error error;
	CHECK(c.read_verified(piece_index_t(0), 0, {out.data(), block}, error));
	auto const lru = c.eviction_candidates(3, [](piece_index_t p) { return p == piece_index_t(2); });
	CHECK_EQ(lru.size(), std::size_t(3));
	CHECK(lru[0] == piece_index_t(1));
	CHECK(lru[1] == piece_index_t(3));
	CHECK(lru[2] == piece_index_t(4));
	// Unverified pieces are never candidates.
	CHECK(c.reserve(piece_index_t(5)));
	auto const all = c.eviction_candidates(10, nullptr);
	CHECK_EQ(all.size(), std::size_t(5));
}

TEST(piece_cache_clear_forgets_blocks_of_a_failed_piece)
{
	fx::temp_dir dir("dtv-cache");
	piece_cache c(dir.file("a.cache"), 2 * block, {2 * block}, {{}}, 2);
	write_piece(c, piece_index_t(0), 9);
	c.clear(piece_index_t(0));
	CHECK(c.has_slot(piece_index_t(0)));
	CHECK(!c.has_data(piece_index_t(0)));
	c.set_verified(piece_index_t(0));
	CHECK(!c.verified(piece_index_t(0)));
	write_piece(c, piece_index_t(0), 9);
	c.set_verified(piece_index_t(0));
	CHECK(c.verified(piece_index_t(0)));
}

TEST(piece_cache_wait_wakes_on_verification_and_cancellation)
{
	fx::temp_dir dir("dtv-cache");
	piece_cache c(dir.file("a.cache"), block, {block, block}, {{}, {}}, 2);
	std::atomic<bool> cancelled{false};
	auto const soon = std::chrono::steady_clock::now() + std::chrono::milliseconds(50);
	CHECK(c.wait_verified(piece_index_t(0), soon, cancelled) == piece_cache::wait_result::timeout);

	std::thread writer([&] {
		std::this_thread::sleep_for(std::chrono::milliseconds(50));
		write_piece(c, piece_index_t(0), 1);
		c.set_verified(piece_index_t(0));
	});
	auto const later = std::chrono::steady_clock::now() + std::chrono::seconds(10);
	CHECK(c.wait_verified(piece_index_t(0), later, cancelled) == piece_cache::wait_result::ready);
	writer.join();

	auto const start = std::chrono::steady_clock::now();
	std::thread canceller([&] {
		std::this_thread::sleep_for(std::chrono::milliseconds(50));
		cancelled = true;
		c.wake_all();
	});
	CHECK(c.wait_verified(piece_index_t(1), later, cancelled) == piece_cache::wait_result::cancelled);
	canceller.join();
	CHECK(std::chrono::steady_clock::now() - start < std::chrono::seconds(2));

	cancelled = false;
	c.close();
	CHECK(c.wait_verified(piece_index_t(1), later, cancelled) == piece_cache::wait_result::closed);
}

TEST(piece_cache_pad_ranges_are_not_downloaded_and_read_as_zeros)
{
	fx::temp_dir dir("dtv-cache");
	// A piece of four blocks whose last block and a half are padding.
	int const size = 4 * block;
	piece_cache c(dir.file("a.cache"), size, {size}, {{{2 * block + block / 2, block + block / 2}}}, 2);
	auto const data = pattern(size, 5);
	lt::storage_error error;
	CHECK(c.write(piece_index_t(0), 0, {data.data(), block}, error));
	CHECK(c.write(piece_index_t(0), block, {data.data() + block, block}, error));
	CHECK(!c.verified(piece_index_t(0)));
	// The third block holds half a block of data and is the last one required.
	CHECK(c.write(piece_index_t(0), 2 * block, {data.data() + 2 * block, block / 2}, error));
	c.set_verified(piece_index_t(0));
	CHECK(c.verified(piece_index_t(0)));
	std::vector<char> out(static_cast<std::size_t>(block), 'x');
	CHECK(c.read_verified(piece_index_t(0), 2 * block, {out.data(), block}, error));
	CHECK(std::equal(out.begin(), out.begin() + block / 2, data.begin() + 2 * block));
	for (int i = block / 2; i < block; ++i) CHECK_EQ(int(out[std::size_t(i)]), 0);
}

TEST(piece_cache_slots_beyond_4_gib)
{
	fx::temp_dir dir("dtv-cache");
	int const piece = 16 << 20;
	int const pieces = 300;
	// Sparse: the test needn't allocate 4.7 GiB.
	piece_cache c(dir.file("a.cache"), piece, std::vector<int>(pieces, piece), std::vector<piece_cache::pad_ranges>(pieces)
		, pieces, false);
	for (int p = 0; p < pieces; ++p) CHECK(c.reserve(piece_index_t(p)));
	// The last slot starts near 4.7 GiB. Only it is written; the rest of the file stays sparse.
	piece_index_t const last(pieces - 1);
	write_piece(c, last, 11);
	c.set_verified(last);
	std::vector<char> out(static_cast<std::size_t>(block));
	lt::storage_error error;
	CHECK(c.read_verified(last, piece - block, {out.data(), block}, error));
	CHECK(!error);
	auto const expected = pattern(piece, 11);
	CHECK(std::equal(out.begin(), out.end(), expected.end() - block));
	CHECK(c.get_stats().allocated_bytes < std::int64_t(64) << 20);
}
