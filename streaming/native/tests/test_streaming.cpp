// The engine against real BitTorrent seeders on loopback: metadata transfer, file selection, bounded caching with
// eviction and re-download, seeks, cancellation, corrupt peers, and 64-bit offsets.

#include "test.hpp"

#include "engine.hpp"
#include "fixtures.hpp"
#include "network_policy.hpp"

#include <libtorrent/hex.hpp>
#include <libtorrent/load_torrent.hpp>
#include <libtorrent/torrent_info.hpp>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <random>
#include <thread>

namespace fx = dtv::fixtures;
using namespace std::chrono_literals;

namespace {

dtv::engine_config local_config(std::string const& cache_dir, std::int64_t cache_bytes)
{
	dtv::engine_config cfg;
	cfg.cache_dir = cache_dir;
	cfg.cache_bytes = cache_bytes;
	cfg.reserve_bytes = 0;
	cfg.listen_interfaces = "127.0.0.1:0";
	cfg.dht = cfg.lsd = cfg.upnp = cfg.natpmp = cfg.pex = false;
	cfg.multiple_connections_per_ip = true;
	if (std::getenv("DTV_TEST_LOG")) cfg.log = [](std::string const& m) { std::printf("LOG %s\n", m.c_str()); };
	return cfg;
}

std::uint64_t open_torrent(dtv::engine& e, std::string const& torrent, std::vector<std::string> const& peers)
{
	dtv::source src;
	src.metainfo = torrent;
	src.peers = peers;
	return e.add(src);
}

// Reads [offset, offset + size) of the reader's file, checking every byte against the content.
void read_checked(dtv::engine& e, std::uint64_t reader, std::uint32_t seed, std::int64_t offset, std::int64_t size
	, std::function<void()> const& each_mib = nullptr)
{
	std::vector<char> buf(64 << 10);
	auto const deadline = std::chrono::steady_clock::now() + 60s;
	std::int64_t pos = offset;
	std::int64_t next_mib = offset + (1 << 20);
	while (pos < offset + size)
	{
		int const want = int(std::min<std::int64_t>(std::int64_t(buf.size()), offset + size - pos));
		int const n = e.read(reader, pos, buf.data(), want, 200ms);
		if (n == dtv::engine::would_block)
		{
			if (std::chrono::steady_clock::now() > deadline) dtv::test::fail(__FILE__, __LINE__, "read stalled");
			continue;
		}
		CHECK(n > 0);
		std::int64_t const bad = fx::first_mismatch(seed, pos, buf.data(), std::size_t(n));
		if (bad >= 0) dtv::test::fail(__FILE__, __LINE__, "wrong byte at " + std::to_string(pos + bad));
		pos += n;
		if (pos >= next_mib)
		{
			if (each_mib) each_mib();
			next_mib += 1 << 20;
		}
	}
}

std::string read_file(std::string const& path)
{
	std::ifstream in(path, std::ios::binary);
	return std::string(std::istreambuf_iterator<char>(in), {});
}

} // namespace

TEST(public_network_filter_blocks_private_reserved_and_mapped_addresses)
{
	auto const filter = dtv::public_network_filter();
	for (char const* address : {"0.0.0.0", "10.0.2.2", "100.64.0.1", "127.0.0.1", "169.254.1.1",
		"172.16.0.1", "172.31.255.255", "192.0.0.1", "192.0.2.1", "192.168.1.1", "198.18.0.1",
		"198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255", "::", "::1", "::ffff:10.0.2.2",
		"::ffff:8.8.8.8", "fc00::1", "fe80::1", "ff02::1", "2001:db8::1"})
		CHECK_EQ(filter.access(lt::make_address(address)), std::uint32_t(lt::ip_filter::blocked));
	for (char const* address : {"1.1.1.1", "8.8.8.8", "100.128.0.1", "172.15.255.255", "172.32.0.1",
		"2001:4860:4860::8888", "2606:4700:4700::1111"})
		CHECK_EQ(filter.access(lt::make_address(address)), std::uint32_t(0));
}

TEST(public_network_sessions_reject_an_explicit_loopback_seeder)
{
	fx::temp_dir dir("dtv-public-network");
	fx::write_content(dir.file("movie.bin"), 1 << 20, 4);
	auto const torrent = fx::make_torrent(dir.file("movie.bin"), 256 << 10, fx::version::v1);
	fx::seeder seed(torrent, dir.path());
	auto config = local_config(dir.file("cache"), 4 << 20);
	config.public_network_only = true;
	dtv::engine e(config);
	dtv::source source;
	auto const info = lt::load_torrent_buffer({torrent.data(), std::ptrdiff_t(torrent.size())}).ti;
	source.info_hash = lt::aux::to_hex(info->info_hashes().v1);
	source.peers = {seed.address()};
	auto const t = e.add(source);
	CHECK_THROWS_KIND(e.wait_metadata(t, 1500ms), dtv::error_kind::timeout);
	CHECK_EQ(e.stats(t).peers, 0);
}

TEST(sequential_read_turns_the_cache_over_within_its_bound)
{
	fx::temp_dir dir("dtv-stream");
	std::int64_t const size = 24 << 20;
	fx::write_content(dir.file("movie.bin"), size, 1);
	std::string const torrent = fx::make_torrent(dir.file("movie.bin"), 256 << 10, fx::version::v1);
	fx::seeder seed(torrent, dir.path());

	std::int64_t const cache = 4 << 20;
	dtv::engine e(local_config(dir.file("cache"), cache));
	auto const t = open_torrent(e, torrent, {seed.address()});
	auto const files = e.wait_metadata(t, 10s);
	CHECK_EQ(files.size(), std::size_t(1));
	CHECK_EQ(files[0].size, size);
	e.select_file(t, 0);
	auto const r = e.open_reader(t, 0);
	CHECK_EQ(e.file_size(r), size);

	std::int64_t most_file = 0, most_allocated = 0;
	int most_used = 0;
	read_checked(e, r, 1, 0, size, [&] {
		auto const st = e.stats(t);
		most_file = std::max(most_file, st.cache.file_bytes);
		most_allocated = std::max(most_allocated, st.cache.allocated_bytes);
		most_used = std::max(most_used, st.cache.used);
	});
	auto const st = e.stats(t);
	// 96 pieces through 15 slots, leaving filesystem overhead inside the total budget.
	CHECK_EQ(st.cache.slots, 15);
	CHECK(st.cache.evicted >= 3 * st.cache.slots);
	CHECK(most_used <= st.cache.slots);
	// The file is preallocated and slots are reused in place, so neither the file nor its storage outgrows the budget.
	CHECK(most_file <= cache);
	CHECK(most_allocated <= cache);
	// Sequential playback downloads each piece about once.
	CHECK(st.total_download < size + size / 4);
	CHECK_EQ(st.disk.blocked_writes, 0);
	// Reading at the end of the file reports the end, not an error.
	char c;
	CHECK_EQ(e.read(r, size, &c, 1, 10ms), 0);
}

TEST(rewind_into_evicted_data_downloads_it_again)
{
	fx::temp_dir dir("dtv-stream");
	std::int64_t const size = 12 << 20;
	fx::write_content(dir.file("movie.bin"), size, 2);
	std::string const torrent = fx::make_torrent(dir.file("movie.bin"), 256 << 10, fx::version::v1);
	fx::seeder seed(torrent, dir.path());

	dtv::engine e(local_config(dir.file("cache"), 2 << 20));
	auto const t = open_torrent(e, torrent, {seed.address()});
	e.wait_metadata(t, 10s);
	e.select_file(t, 0);
	auto const r = e.open_reader(t, 0);
	read_checked(e, r, 2, 0, size);
	// The seeder was told of every eviction, so it never mistakes the client for a seed and drops it.
	std::this_thread::sleep_for(500ms);
	CHECK_EQ(e.stats(t).peers, 1);
	auto const before = e.stats(t);
	// The start of the file was evicted long ago; it must come back intact.
	read_checked(e, r, 2, 0, 3 << 20);
	auto const after = e.stats(t);
	CHECK(after.total_download >= before.total_download + (2 << 20));
	CHECK(after.cache.evicted > before.cache.evicted);
}

TEST(seeks_forward_and_back_read_the_right_bytes)
{
	fx::temp_dir dir("dtv-stream");
	std::int64_t const size = 16 << 20;
	fx::write_content(dir.file("movie.bin"), size, 3);
	std::string const torrent = fx::make_torrent(dir.file("movie.bin"), 128 << 10, fx::version::v1);
	fx::seeder seed(torrent, dir.path());

	dtv::engine e(local_config(dir.file("cache"), 2 << 20));
	auto const t = open_torrent(e, torrent, {seed.address()});
	e.wait_metadata(t, 10s);
	e.select_file(t, 0);
	auto const r = e.open_reader(t, 0);
	read_checked(e, r, 3, 0, 1 << 20);
	read_checked(e, r, 3, size * 9 / 10, 1 << 20);
	read_checked(e, r, 3, size * 4 / 10 + 12345, 1 << 20);
	read_checked(e, r, 3, 100, 1 << 20);
	auto const st = e.stats(t);
	// Only around the four positions was anything downloaded.
	CHECK(st.total_download < 10 << 20);
}

namespace {

// Three files, so the selected one starts and ends inside pieces (v1) or after padding (v2, hybrid).
void stream_middle_file(fx::version v)
{
	fx::temp_dir dir("dtv-stream");
	std::string const root = dir.file("series");
	std::filesystem::create_directories(root);
	std::int64_t const sizes[] = {3 << 20, (5 << 20) + 1234, (2 << 20) + 77};
	for (int i = 0; i < 3; ++i) fx::write_content(root + "/e" + std::to_string(i + 1) + ".bin", sizes[i], 10 + i);
	std::string const torrent = fx::make_torrent(root, 64 << 10, v);
	fx::seeder seed(torrent, dir.path());

	dtv::engine e(local_config(dir.file("cache"), 1 << 20));
	auto const t = open_torrent(e, torrent, {seed.address()});
	auto const files = e.wait_metadata(t, 10s);
	// Pad files are not offered.
	CHECK_EQ(files.size(), std::size_t(3));
	auto const middle = std::find_if(files.begin(), files.end(), [](auto const& f) {
		return f.path.find("e2.bin") != std::string::npos; });
	CHECK(middle != files.end());
	CHECK_EQ(middle->size, sizes[1]);
	e.select_file(t, middle->index);
	auto const r = e.open_reader(t, middle->index);
	read_checked(e, r, 11, 0, sizes[1]);
	read_checked(e, r, 11, 4096, 1 << 20);
	auto const st = e.stats(t);
	CHECK(st.cache.evicted > 0);
	// The other files' pieces were not downloaded, beyond the pieces shared at the edges.
	CHECK(st.total_download < sizes[1] + (2 << 20));
}

} // namespace

TEST(v1_multi_file_torrent_streams_one_file) { stream_middle_file(fx::version::v1); }
TEST(v2_torrent_streams_one_file) { stream_middle_file(fx::version::v2); }
TEST(hybrid_torrent_streams_one_file) { stream_middle_file(fx::version::hybrid); }

TEST(magnet_metadata_arrives_over_bittorrent)
{
	fx::temp_dir dir("dtv-stream");
	fx::write_content(dir.file("clip.bin"), 3 << 20, 4);
	std::string const torrent = fx::make_torrent(dir.file("clip.bin"), 64 << 10, fx::version::hybrid);
	fx::seeder seed(torrent, dir.path());
	auto const info = lt::load_torrent_buffer({torrent.data(), std::ptrdiff_t(torrent.size())}).ti;

	dtv::engine e(local_config(dir.file("cache"), 1 << 20));
	dtv::source src;
	// Only the v1 hash: the v2 hashes arrive with the metadata.
	src.info_hash = lt::aux::to_hex(info->info_hashes().v1);
	src.peers = {seed.address()};
	auto const t = e.add(src);
	auto const files = e.wait_metadata(t, 15s);
	CHECK_EQ(files.size(), std::size_t(1));
	e.select_file(t, files[0].index);
	auto const r = e.open_reader(t, files[0].index);
	read_checked(e, r, 4, 0, 3 << 20);

	dtv::source magnet;
	magnet.magnet = "magnet:?xt=urn:btih:" + lt::aux::to_hex(info->info_hashes().v1) + "&x.pe=" + seed.address();
	dtv::engine e2(local_config(dir.file("cache2"), 1 << 20));
	auto const t2 = e2.add(magnet);
	CHECK_EQ(e2.wait_metadata(t2, 15s).size(), std::size_t(1));
}

TEST(metadata_wait_times_out_without_peers)
{
	fx::temp_dir dir("dtv-stream");
	dtv::engine e(local_config(dir.file("cache"), 1 << 20));
	dtv::source src;
	src.info_hash = std::string(40, 'a');
	auto const t = e.add(src);
	CHECK_THROWS_KIND(e.wait_metadata(t, 300ms), dtv::error_kind::timeout);
	std::atomic<bool> cancelled{true};
	CHECK_THROWS_KIND(e.wait_metadata(t, 10s, &cancelled), dtv::error_kind::cancelled);
	CHECK_EQ(e.stats(t).state, std::string("metadata"));
}

TEST(blocked_reads_wake_on_cancel_and_on_close)
{
	fx::temp_dir dir("dtv-stream");
	fx::write_content(dir.file("movie.bin"), 2 << 20, 5);
	std::string const torrent = fx::make_torrent(dir.file("movie.bin"), 64 << 10, fx::version::v1);
	dtv::engine e(local_config(dir.file("cache"), 1 << 20));
	// No peers: the data never arrives.
	auto const t = open_torrent(e, torrent, {});
	e.wait_metadata(t, 1s);
	e.select_file(t, 0);
	auto const r = e.open_reader(t, 0);

	auto blocked_read = [&e](std::uint64_t reader) {
		char buf[16];
		for (;;)
		{
			int const n = e.read(reader, 0, buf, sizeof(buf), 5s);
			if (n != dtv::engine::would_block) return n;
		}
	};
	auto const start = std::chrono::steady_clock::now();
	std::thread canceller([&] { std::this_thread::sleep_for(100ms); e.cancel(r); });
	CHECK_THROWS_KIND(blocked_read(r), dtv::error_kind::cancelled);
	canceller.join();
	CHECK(std::chrono::steady_clock::now() - start < 2s);
	// A cancelled reader stays cancelled; another reader is unaffected until the torrent closes.
	CHECK_THROWS_KIND(blocked_read(r), dtv::error_kind::cancelled);
	auto const r2 = e.open_reader(t, 0);
	std::thread closer([&] { std::this_thread::sleep_for(100ms); e.remove(t); });
	CHECK_THROWS_KIND(blocked_read(r2), dtv::error_kind::closed);
	closer.join();
	CHECK_THROWS_KIND(e.stats(t), dtv::error_kind::invalid_handle);
}

TEST(shutdown_with_inflight_hashes_drains_callbacks)
{
	fx::temp_dir dir("dtv-shutdown");
	fx::write_content(dir.file("movie.bin"), 8 << 20, 15);
	std::string const torrent = fx::make_torrent(dir.file("movie.bin"), 256 << 10, fx::version::hybrid);
	fx::seeder seed(torrent, dir.path());
	for (int cycle = 0; cycle < 12; ++cycle)
	{
		dtv::engine e(local_config(dir.file("cache"), 2 << 20));
		auto const t = open_torrent(e, torrent, {seed.address()});
		e.wait_metadata(t, 5s);
		e.select_file(t, 0);
		auto const r = e.open_reader(t, 0);
		char byte;
		auto const deadline = std::chrono::steady_clock::now() + 15s;
		while (e.read(r, 0, &byte, 1, 1s) == dtv::engine::would_block)
			CHECK(std::chrono::steady_clock::now() < deadline);
		// Other pieces are being written/hashed when the session is destroyed.
	}
}

TEST(corrupt_peer_data_fails_the_hash_and_comes_again_from_a_good_peer)
{
	fx::temp_dir dir("dtv-stream");
	std::int64_t const size = 4 << 20;
	std::filesystem::create_directories(dir.file("good"));
	std::filesystem::create_directories(dir.file("bad"));
	fx::write_content(dir.file("good/movie.bin"), size, 6);
	std::string const torrent = fx::make_torrent(dir.file("good/movie.bin"), 64 << 10, fx::version::v1);
	// The corrupt copy differs in one byte of every piece, and its seeder serves it unchecked.
	std::string data = read_file(dir.file("good/movie.bin"));
	for (std::size_t i = 1000; i < data.size(); i += 64 << 10) data[i] = char(data[i] ^ 0x5a);
	std::ofstream(dir.file("bad/movie.bin"), std::ios::binary).write(data.data(), std::streamsize(data.size()));
	fx::seeder_options unchecked;
	unchecked.verify = false;
	fx::seeder bad(torrent, dir.file("bad"), unchecked);
	fx::seeder good(torrent, dir.file("good"));

	dtv::engine e(local_config(dir.file("cache"), 1 << 20));
	auto const t = open_torrent(e, torrent, {bad.address()});
	e.wait_metadata(t, 10s);
	e.select_file(t, 0);
	auto const r = e.open_reader(t, 0);
	std::string error;
	std::thread reader([&] {
		try { read_checked(e, r, 6, 0, size); }
		catch (std::exception const& ex) { error = ex.what(); }
	});
	auto const deadline = std::chrono::steady_clock::now() + 20s;
	while (e.stats(t).hash_failures == 0 && std::chrono::steady_clock::now() < deadline) std::this_thread::sleep_for(20ms);
	int const failures = e.stats(t).hash_failures;
	// Only a good peer can complete the file; the reader never sees a corrupt byte.
	e.add_peers(t, {good.address()});
	reader.join();
	CHECK(failures > 0);
	CHECK_EQ(error, std::string());
}

TEST(unusable_torrents_are_refused_with_a_reason)
{
	fx::temp_dir dir("dtv-stream");
	fx::write_content(dir.file("movie.bin"), 1 << 20, 7);
	dtv::engine e(local_config(dir.file("cache"), 1 << 20));
	dtv::source garbage;
	garbage.metainfo = "d4:infoi1ee";
	CHECK_THROWS_KIND(e.add(garbage), dtv::error_kind::bad_source);
	dtv::source nothing;
	CHECK_THROWS_KIND(e.add(nothing), dtv::error_kind::bad_source);
	dtv::source bad_hash;
	bad_hash.info_hash = "1234";
	CHECK_THROWS_KIND(e.add(bad_hash), dtv::error_kind::bad_source);

	std::string const huge = fx::make_torrent(dir.file("movie.bin"), 32 << 20, fx::version::v1);
	CHECK_THROWS_KIND(open_torrent(e, huge, {}), dtv::error_kind::piece_too_large);
	// Four slots of 512 KiB don't fit a 1 MiB cache.
	std::string const big = fx::make_torrent(dir.file("movie.bin"), 512 << 10, fx::version::v1);
	CHECK_THROWS_KIND(open_torrent(e, big, {}), dtv::error_kind::cache_too_small);

	std::string const fine = fx::make_torrent(dir.file("movie.bin"), 64 << 10, fx::version::v1);
	auto const t = open_torrent(e, fine, {});
	e.wait_metadata(t, 1s);
	CHECK_THROWS_KIND(e.select_file(t, 3), dtv::error_kind::invalid_file);
	CHECK_THROWS_KIND(e.select_file(t, -1), dtv::error_kind::invalid_file);
	CHECK_THROWS_KIND(e.open_reader(t, 1), dtv::error_kind::invalid_file);
	CHECK_THROWS_KIND(e.read(12345, 0, nullptr, 0, 1ms), dtv::error_kind::invalid_handle);
}

TEST(offsets_beyond_4_gib_map_to_the_right_bytes)
{
	fx::temp_dir dir("dtv-stream");
	std::string const root = dir.file("large");
	std::filesystem::create_directories(root);
	// A sparse first file pushes the second past 4 GiB, starting inside a piece.
	std::int64_t const first = (std::int64_t(17) << 28) + 123;
	fx::write_content(root + "/a.bin", first, 8, first);
	fx::write_content(root + "/b.bin", 3 << 20, 9);
	std::string const torrent = fx::make_torrent(root, 4 << 20, fx::version::v1);
	fx::seeder seed(torrent, dir.path());

	dtv::engine e(local_config(dir.file("cache"), (16 << 20) + dtv::cache_storage_overhead));
	auto const t = open_torrent(e, torrent, {seed.address()});
	auto const files = e.wait_metadata(t, 10s);
	auto const b = std::find_if(files.begin(), files.end(), [](auto const& f) { return f.path.find("b.bin") != std::string::npos; });
	CHECK(b != files.end());
	e.select_file(t, b->index);
	auto const r = e.open_reader(t, b->index);
	read_checked(e, r, 9, 0, 3 << 20);
}

TEST(concurrent_readers_and_seeks_keep_data_and_resources_consistent)
{
	fx::temp_dir dir("dtv-stream");
	std::int64_t const size = 16 << 20;
	fx::write_content(dir.file("movie.bin"), size, 12);
	std::string const torrent = fx::make_torrent(dir.file("movie.bin"), 64 << 10, fx::version::v1);
	fx::seeder seed(torrent, dir.path());

	dtv::engine e(local_config(dir.file("cache"), 2 << 20));
	auto const t = open_torrent(e, torrent, {seed.address()});
	e.wait_metadata(t, 10s);
	e.select_file(t, 0);
	std::atomic<bool> stop{false};
	std::vector<std::string> errors(3);
	std::thread sequential([&] {
		try { read_checked(e, e.open_reader(t, 0), 12, 0, size); }
		catch (std::exception const& ex) { errors[0] = ex.what(); }
	});
	std::thread seeking([&] {
		try
		{
			std::mt19937_64 rng(42);
			auto const r = e.open_reader(t, 0);
			for (int i = 0; i < 40; ++i)
			{
				std::int64_t const pos = std::int64_t(rng() % std::uint64_t(size - (256 << 10)));
				read_checked(e, r, 12, pos, 200 << 10);
			}
			e.close_reader(r);
		}
		catch (std::exception const& ex) { errors[1] = ex.what(); }
	});
	std::thread watcher([&] {
		try
		{
			while (!stop)
			{
				auto const st = e.stats(t);
				if (st.cache.used > st.cache.slots) errors[2] = "more slots used than exist";
				std::this_thread::sleep_for(5ms);
			}
		}
		catch (std::exception const& ex) { errors[2] = ex.what(); }
	});
	sequential.join();
	seeking.join();
	stop = true;
	watcher.join();
	for (auto const& err : errors) CHECK_EQ(err, std::string());
	// Once idle, nothing stays queued, blocked or pinned.
	std::this_thread::sleep_for(300ms);
	auto const st = e.stats(t);
	CHECK_EQ(st.disk.blocked_writes, 0);
	CHECK_EQ(st.disk.queued_write_bytes, std::int64_t(0));
	CHECK_EQ(st.cache.pinned, 0);
	CHECK(st.cache.allocated_bytes <= std::int64_t(2) << 20);
}
