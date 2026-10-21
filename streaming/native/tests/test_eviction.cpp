// Regressions for the libtorrent eviction patch (patches/libtorrent), on plain sessions using the bounded cache:
// a seed or finished torrent forgets pieces and downloads them again, other peers learn about it by lt_donthave
// or by being disconnected, and queued uploads of evicted pieces are rejected. Move to another libtorrent only
// after these pass.

#include "test.hpp"

#include "cache_disk_io.hpp"
#include "fixtures.hpp"

#include <libtorrent/add_torrent_params.hpp>
#include <libtorrent/alert_types.hpp>
#include <libtorrent/load_torrent.hpp>
#include <libtorrent/peer_info.hpp>
#include <libtorrent/session.hpp>
#include <libtorrent/torrent_info.hpp>
#include <libtorrent/torrent_status.hpp>

#include <arpa/inet.h>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <filesystem>
#include <mutex>
#include <netinet/in.h>
#include <sys/socket.h>
#include <thread>
#include <unistd.h>

namespace fx = dtv::fixtures;
using dtv::piece_index_t;
using namespace std::chrono_literals;

namespace {

lt::settings_pack loopback_settings()
{
	lt::settings_pack sp;
	sp.set_str(lt::settings_pack::listen_interfaces, "127.0.0.1:0");
	sp.set_bool(lt::settings_pack::enable_dht, false);
	sp.set_str(lt::settings_pack::dht_bootstrap_nodes, "");
	sp.set_bool(lt::settings_pack::enable_lsd, false);
	sp.set_bool(lt::settings_pack::enable_upnp, false);
	sp.set_bool(lt::settings_pack::enable_natpmp, false);
	sp.set_bool(lt::settings_pack::allow_multiple_connections_per_ip, true);
	sp.set_bool(lt::settings_pack::close_redundant_connections, false);
	sp.set_int(lt::settings_pack::unchoke_interval, 1);
	sp.set_int(lt::settings_pack::alert_mask, lt::alert_category::error | lt::alert_category::status
		| lt::alert_category::piece_progress);
	// A seeder drops a client that became a seed; after an eviction it must come back quickly.
	sp.set_int(lt::settings_pack::min_reconnect_time, 1);
	return sp;
}

int wait_for_port(lt::session& s)
{
	for (int i = 0; i < 200; ++i)
	{
		if (int const port = s.listen_port()) return port;
		std::this_thread::sleep_for(10ms);
	}
	throw std::runtime_error("no listen port");
}

// A session whose torrents live in the bounded cache, as in the app.
struct client
{
	std::atomic<dtv::cache_disk_io*> disk{nullptr};
	std::unique_ptr<lt::session> session;
	lt::torrent_handle handle;
	std::shared_ptr<dtv::piece_cache> cache;
	lt::sha1_hash info_hash;

	client(std::string const& dir, lt::settings_pack sp = loopback_settings())
	{
		std::filesystem::create_directories(dir);
		lt::session_params params(std::move(sp));
		dtv::cache_config cfg;
		cfg.directory = dir;
		cfg.cache_bytes = 16 << 20;
		params.disk_io_constructor = dtv::cache_disk_io_constructor(cfg, [this](dtv::cache_disk_io* d) { disk = d; });
		session = std::make_unique<lt::session>(std::move(params));
	}

	void add(std::string const& torrent, std::vector<lt::tcp::endpoint> const& peers, lt::torrent_flags_t flags = {})
	{
		lt::add_torrent_params p = lt::load_torrent_buffer({torrent.data(), std::ptrdiff_t(torrent.size())});
		info_hash = p.ti->info_hashes().get_best();
		p.save_path = ".";
		p.flags = (p.flags & ~(lt::torrent_flags::auto_managed | lt::torrent_flags::paused)) | flags
			| lt::torrent_flags::disable_dht | lt::torrent_flags::disable_lsd | lt::torrent_flags::disable_pex;
		p.peers = peers;
		handle = session->add_torrent(std::move(p));
	}

	// Handles alerts, marking finished pieces verified in the cache as the engine does, until done() holds.
	bool pump(std::function<bool()> const& done, std::chrono::milliseconds timeout = 20s)
	{
		auto const deadline = std::chrono::steady_clock::now() + timeout;
		while (std::chrono::steady_clock::now() < deadline)
		{
			if (!cache && disk.load()) cache = disk.load()->find(info_hash);
			std::vector<lt::alert*> alerts;
			session->pop_alerts(&alerts);
			for (lt::alert* a : alerts)
				if (auto* pf = lt::alert_cast<lt::piece_finished_alert>(a); pf && cache) cache->set_verified(pf->piece_index);
			if (done()) return true;
			session->wait_for_alert(20ms);
		}
		return done();
	}

	std::vector<piece_index_t> evict(std::vector<piece_index_t> const& pieces)
	{
		std::mutex m;
		std::condition_variable cv;
		bool finished = false;
		std::vector<piece_index_t> result;
		cache->begin_release(pieces);
		auto cache_ref = cache;
		auto* d = disk.load();
		handle.evict_pieces(pieces, [&, cache_ref, d](std::vector<piece_index_t> evicted) {
			d->release(cache_ref, evicted);
			std::lock_guard<std::mutex> lock(m);
			result = std::move(evicted);
			finished = true;
			cv.notify_all();
		});
		std::unique_lock<std::mutex> lock(m);
		cv.wait_for(lock, 10s, [&] { return finished; });
		CHECK(finished);
		return result;
	}

	bool piece_matches(piece_index_t p, std::uint32_t seed, std::int64_t file_offset_of_piece, int size)
	{
		std::vector<char> buf(static_cast<std::size_t>(size));
		lt::storage_error error;
		if (!cache->read_verified(p, 0, {buf.data(), size}, error) || error) return false;
		return fx::first_mismatch(seed, file_offset_of_piece, buf.data(), buf.size()) < 0;
	}
};

void seed_evicts_and_downloads_again(fx::version v)
{
	fx::temp_dir dir("dtv-evict");
	std::int64_t const size = 2 << 20;
	fx::write_content(dir.file("movie.bin"), size, 21);
	std::string const torrent = fx::make_torrent(dir.file("movie.bin"), 64 << 10, v);
	fx::seeder seed(torrent, dir.path());

	client c(dir.file("cache"));
	c.add(torrent, {{lt::make_address("127.0.0.1"), std::uint16_t(seed.port())}});
	CHECK(c.pump([&] { return c.handle.status().is_seeding; }));
	CHECK(c.pump([&] { return c.cache && c.cache->verified(piece_index_t(3)) && c.cache->verified(piece_index_t(10)); }));
	std::int64_t const downloaded = c.handle.status().total_payload_download;

	auto const evicted = c.evict({piece_index_t(3), piece_index_t(10), piece_index_t(9999)});
	CHECK_EQ(evicted.size(), std::size_t(2));
	auto st = c.handle.status();
	CHECK(!st.is_seeding);
	CHECK(!c.handle.have_piece(piece_index_t(3)));
	CHECK(c.handle.have_piece(piece_index_t(4)));
	CHECK(!c.cache->verified(piece_index_t(3)));

	// The rebuilt picker kept the default priority, so the pieces come back and pass their hashes again.
	CHECK(c.pump([&] { return c.handle.status().is_seeding; }));
	CHECK(c.pump([&] { return c.cache->verified(piece_index_t(3)) && c.cache->verified(piece_index_t(10)); }));
	CHECK(c.piece_matches(piece_index_t(3), 21, 3 * (64 << 10), 64 << 10));
	CHECK(c.piece_matches(piece_index_t(10), 21, 10 * (64 << 10), 64 << 10));
	st = c.handle.status();
	CHECK(st.total_payload_download >= downloaded + 2 * (64 << 10));
	CHECK(st.state == lt::torrent_status::seeding);
	CHECK(!st.errc);
}

} // namespace

TEST(eviction_v1_seed_downloads_evicted_pieces_again) { seed_evicts_and_downloads_again(fx::version::v1); }
TEST(eviction_v2_seed_downloads_evicted_pieces_again) { seed_evicts_and_downloads_again(fx::version::v2); }
TEST(eviction_hybrid_seed_downloads_evicted_pieces_again) { seed_evicts_and_downloads_again(fx::version::hybrid); }

TEST(eviction_of_unwanted_pieces_keeps_a_finished_torrent_finished)
{
	fx::temp_dir dir("dtv-evict");
	fx::write_content(dir.file("movie.bin"), 2 << 20, 22);
	std::string const torrent = fx::make_torrent(dir.file("movie.bin"), 64 << 10, fx::version::v1);
	fx::seeder seed(torrent, dir.path());

	client c(dir.file("cache"));
	c.add(torrent, {{lt::make_address("127.0.0.1"), std::uint16_t(seed.port())}}, lt::torrent_flags::default_dont_download);
	CHECK(c.pump([&] { return c.handle.status().has_metadata; }));
	std::vector<lt::download_priority_t> prio(32, lt::dont_download);
	for (int i = 0; i < 8; ++i) prio[std::size_t(i)] = lt::default_priority;
	c.handle.prioritize_pieces(prio);
	CHECK(c.pump([&] { return c.handle.status().state == lt::torrent_status::finished; }));
	CHECK(c.pump([&] { return c.cache && c.cache->verified(piece_index_t(5)); }));

	prio[2] = prio[5] = lt::dont_download;
	c.handle.prioritize_pieces(prio);
	CHECK_EQ(c.evict({piece_index_t(2), piece_index_t(5)}).size(), std::size_t(2));
	std::int64_t const downloaded = c.handle.status().total_payload_download;
	c.pump([] { return false; }, 500ms);
	auto st = c.handle.status();
	CHECK(st.state == lt::torrent_status::finished);
	CHECK(!c.handle.have_piece(piece_index_t(2)));
	CHECK_EQ(st.total_payload_download, downloaded);

	// Wanting a piece again resumes downloading it.
	prio[2] = lt::default_priority;
	c.handle.prioritize_pieces(prio);
	CHECK(c.pump([&] { return c.handle.have_piece(piece_index_t(2)) && c.cache->verified(piece_index_t(2)); }));
	CHECK(c.piece_matches(piece_index_t(2), 22, 2 * (64 << 10), 64 << 10));
	CHECK(!c.handle.have_piece(piece_index_t(5)));
}

TEST(eviction_is_announced_to_peers_supporting_lt_donthave)
{
	fx::temp_dir dir("dtv-evict");
	fx::write_content(dir.file("movie.bin"), 2 << 20, 23);
	std::string const torrent = fx::make_torrent(dir.file("movie.bin"), 64 << 10, fx::version::v1);
	fx::seeder seed(torrent, dir.path());
	client c(dir.file("cache"));
	c.add(torrent, {{lt::make_address("127.0.0.1"), std::uint16_t(seed.port())}});
	CHECK(c.pump([&] { return c.handle.status().is_seeding; }));
	int const port = wait_for_port(*c.session);

	// A slow libtorrent leecher, so it keeps wanting pieces and stays a downloading peer.
	lt::settings_pack sp = loopback_settings();
	sp.set_int(lt::settings_pack::download_rate_limit, 2000);
	sp.set_int(lt::settings_pack::alert_mask, lt::alert_category::peer | lt::alert_category::error);
	lt::session leecher(lt::session_params(std::move(sp)));
	lt::add_torrent_params p = lt::load_torrent_buffer({torrent.data(), std::ptrdiff_t(torrent.size())});
	std::filesystem::create_directories(dir.file("leecher"));
	p.save_path = dir.file("leecher");
	p.flags &= ~(lt::torrent_flags::auto_managed | lt::torrent_flags::paused);
	p.peers = {{lt::make_address("127.0.0.1"), std::uint16_t(port)}};
	lt::torrent_handle const l = leecher.add_torrent(std::move(p));

	auto peers = [&] {
		std::vector<lt::peer_info> v;
		l.get_peer_info(v);
		return v;
	};
	// The leecher's only peer is the client, though once the client downloads again it may also connect
	// back and keep that connection instead.
	auto client_has = [&](piece_index_t piece) {
		for (auto const& peer : peers())
			if (peer.pieces.size() > 0 && peer.pieces.get_bit(piece)) return true;
		return false;
	};
	auto connected = [&] { return !peers().empty(); };
	int resync_disconnects = 0;
	auto leecher_alerts = [&] {
		std::vector<lt::alert*> alerts;
		leecher.pop_alerts(&alerts);
		for (lt::alert* a : alerts)
			if (auto* d = lt::alert_cast<lt::peer_disconnected_alert>(a); d && d->error == lt::errors::pieces_evicted)
				++resync_disconnects;
	};
	CHECK(c.pump([&] { leecher_alerts(); return client_has(piece_index_t(7)); }));
	CHECK_EQ(c.evict({piece_index_t(7)}).size(), std::size_t(1));
	// The leecher learns by lt_donthave. Once the client downloads again it may also connect back, and
	// libtorrent keeps one of the two connections; either way the leecher is never dropped to resynchronize.
	CHECK(c.pump([&] { leecher_alerts(); return connected() && !client_has(piece_index_t(7)) && client_has(piece_index_t(8)); }));
	c.pump([&] { leecher_alerts(); return false; }, 300ms);
	CHECK_EQ(resync_disconnects, 0);
}

namespace {

// Just enough of the BitTorrent wire protocol to act as a downloading peer the client didn't expect.
struct raw_peer
{
	struct options
	{
		bool fast = true;
		// the extension protocol, advertising lt_donthave
		bool donthave = false;
		// a small receive buffer, so unread data soon holds the client's uploads back
		int receive_buffer = 0;
	};

	static constexpr int our_donthave_id = 7;
	int fd = -1;
	std::string inbox;
	bool closed = false;

	raw_peer(int port, lt::sha1_hash const& info_hash, options const& o)
	{
		fd = ::socket(AF_INET, SOCK_STREAM, 0);
		if (o.receive_buffer > 0) ::setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &o.receive_buffer, sizeof(o.receive_buffer));
		sockaddr_in addr{};
		addr.sin_family = AF_INET;
		addr.sin_port = htons(std::uint16_t(port));
		addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
		if (::connect(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) throw std::runtime_error("connect");
		timeval tv{0, 100000};
		::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
		std::string hs("\x13" "BitTorrent protocol", 20);
		char reserved[8] = {};
		if (o.donthave) reserved[5] |= 0x10;
		if (o.fast) reserved[7] |= 0x04;
		hs.append(reserved, 8);
		hs.append(reinterpret_cast<char const*>(info_hash.data()), 20);
		// Peer ids must differ, or the client drops the second connection as a duplicate.
		static std::atomic<int> next{0};
		char id[21];
		std::snprintf(id, sizeof(id), "-DT0001-rawpeer%05d", next++);
		hs.append(id, 20);
		send(hs);
		if (o.donthave)
		{
			std::string const ext = "d1:md11:lt_donthavei" + std::to_string(our_donthave_id) + "eee";
			message(20, std::string(1, '\0') + ext);
		}
	}

	~raw_peer() { if (fd >= 0) ::close(fd); }

	void send(std::string const& data) { CHECK(::send(fd, data.data(), data.size(), 0) == ssize_t(data.size())); }

	void message(std::uint8_t id, std::string const& payload = {})
	{
		std::uint32_t const len = htonl(std::uint32_t(payload.size() + 1));
		std::string m(reinterpret_cast<char const*>(&len), 4);
		m.push_back(char(id));
		send(m + payload);
	}

	void request(int piece, int offset, int length)
	{
		std::uint32_t const fields[] = {htonl(std::uint32_t(piece)), htonl(std::uint32_t(offset)), htonl(std::uint32_t(length))};
		message(6, std::string(reinterpret_cast<char const*>(fields), sizeof(fields)));
	}

	// Reads until `done` holds, the connection closes, or the timeout passes.
	bool receive_until(std::function<bool()> const& done, std::chrono::milliseconds timeout = 10s)
	{
		auto const deadline = std::chrono::steady_clock::now() + timeout;
		std::vector<char> buf(1 << 16);
		while (!done() && !closed && std::chrono::steady_clock::now() < deadline)
		{
			ssize_t const n = ::recv(fd, buf.data(), buf.size(), 0);
			if (n == 0 || (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK)) closed = true;
			else if (n > 0) inbox.append(buf.data(), std::size_t(n));
		}
		return done();
	}

	struct msg { int id; std::string payload; };

	// Messages after the 68-byte handshake.
	std::vector<msg> messages() const
	{
		std::vector<msg> ret;
		std::size_t pos = 68;
		while (pos + 4 <= inbox.size())
		{
			std::uint32_t len;
			std::memcpy(&len, inbox.data() + pos, 4);
			len = ntohl(len);
			if (pos + 4 + len > inbox.size()) break;
			if (len > 0) ret.push_back({std::uint8_t(inbox[pos + 4]), inbox.substr(pos + 5, len - 1)});
			pos += 4 + len;
		}
		return ret;
	}

	static int piece_of(std::string const& payload)
	{
		std::uint32_t v;
		std::memcpy(&v, payload.data(), 4);
		return int(ntohl(v));
	}

	bool got(int id) const
	{
		for (auto const& m : messages()) if (m.id == id) return true;
		return false;
	}
};

constexpr int msg_unchoke = 1;
constexpr int msg_interested = 2;
constexpr int msg_have_all = 14;
constexpr int msg_have_none = 15;
constexpr int msg_reject = 16;
constexpr int msg_extended = 20;

// A client that has downloaded the whole torrent from a seeder, for peers to connect to.
struct seeded_client
{
	fx::temp_dir dir{"dtv-evict"};
	std::string torrent;
	std::unique_ptr<fx::seeder> seed;
	std::unique_ptr<client> c;
	int port = 0;
	lt::sha1_hash v1;

	explicit seeded_client(std::uint32_t content, lt::settings_pack sp = loopback_settings())
	{
		fx::write_content(dir.file("movie.bin"), 2 << 20, content);
		torrent = fx::make_torrent(dir.file("movie.bin"), 64 << 10, fx::version::v1);
		seed = std::make_unique<fx::seeder>(torrent, dir.path());
		c = std::make_unique<client>(dir.file("cache"), std::move(sp));
		c->add(torrent, {{lt::make_address("127.0.0.1"), std::uint16_t(seed->port())}});
		CHECK(c->pump([&] { return c->handle.status().is_seeding; }));
		port = wait_for_port(*c->session);
		v1 = lt::load_torrent_buffer({torrent.data(), std::ptrdiff_t(torrent.size())}).ti->info_hashes().v1;
	}

	void unchoked(raw_peer& peer)
	{
		CHECK(c->pump([&] { return peer.receive_until([&] { return peer.got(msg_unchoke); }, 50ms); }));
	}
};

} // namespace

TEST(eviction_rejects_queued_uploads_and_tells_peers_supporting_lt_donthave)
{
	lt::settings_pack sp = loopback_settings();
	// A small send buffer: requests behind it stay queued while the peer isn't reading.
	sp.set_int(lt::settings_pack::send_buffer_watermark, 32 << 10);
	sp.set_int(lt::settings_pack::send_buffer_low_watermark, 16 << 10);
	sp.set_int(lt::settings_pack::send_buffer_watermark_factor, 1);
	seeded_client s(24, std::move(sp));
	raw_peer::options o;
	o.donthave = true;
	o.receive_buffer = 4096;
	raw_peer peer(s.port, s.v1, o);
	peer.message(msg_have_none);
	peer.message(msg_interested);
	s.unchoked(peer);
	// Requests for every other piece, which the peer doesn't read, fill the client's buffers, so the requests
	// for piece 12 behind them wait in its queue.
	for (int p = 0; p < 32; ++p)
		for (int b = 0; b < 4 && p != 12; ++b) peer.request(p, b * (16 << 10), 16 << 10);
	for (int b = 0; b < 4; ++b) peer.request(12, b * (16 << 10), 16 << 10);
	s.c->pump([] { return false; }, 500ms);

	CHECK_EQ(s.c->evict({piece_index_t(12)}).size(), std::size_t(1));
	auto rejected = [&] {
		int count = 0;
		for (auto const& m : peer.messages()) if (m.id == msg_reject && raw_peer::piece_of(m.payload) == 12) ++count;
		return count;
	};
	auto told = [&] {
		for (auto const& m : peer.messages())
			if (m.id == msg_extended && !m.payload.empty() && m.payload[0] == char(raw_peer::our_donthave_id)
				&& raw_peer::piece_of(m.payload.substr(1)) == 12)
				return true;
		return false;
	};
	CHECK(peer.receive_until([&] { return rejected() == 4 && told(); }, 20s));
	CHECK(!peer.closed);
	// The client gets the piece back from the seeder.
	CHECK(s.c->pump([&] { return s.c->handle.have_piece(piece_index_t(12)) && s.c->cache->verified(piece_index_t(12)); }));
}

TEST(eviction_disconnects_downloading_peers_without_lt_donthave_but_not_seeds)
{
	seeded_client s(25);
	raw_peer::options plain;
	plain.fast = false;
	raw_peer leecher(s.port, s.v1, plain);
	// An empty bitfield: a peer that wants everything.
	leecher.message(5, std::string(4, '\0'));
	leecher.message(msg_interested);
	raw_peer seed_peer(s.port, s.v1, raw_peer::options{});
	seed_peer.message(msg_have_all);
	s.c->pump([&] {
		leecher.receive_until([] { return false; }, 10ms);
		seed_peer.receive_until([] { return false; }, 10ms);
		return leecher.messages().size() > 0 && seed_peer.messages().size() > 0;
	});
	CHECK(!leecher.closed);

	CHECK_EQ(s.c->evict({piece_index_t(3)}).size(), std::size_t(1));
	// The leecher can't be told it lost the piece, so it is disconnected to resynchronize; the seed never
	// downloads from the client and stays.
	CHECK(s.c->pump([&] { leecher.receive_until([&] { return leecher.closed; }, 20ms); return leecher.closed; }, 10s));
	seed_peer.receive_until([] { return false; }, 300ms);
	CHECK(!seed_peer.closed);
}
