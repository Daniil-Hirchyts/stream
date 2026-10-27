#include "test.hpp"
#include "engine.hpp"
#include "fixtures.hpp"
#include <libtorrent/bdecode.hpp>
#include <libtorrent/bencode.hpp>
#include <libtorrent/entry.hpp>
#include <libtorrent/load_torrent.hpp>
#include <libtorrent/torrent_info.hpp>
#include <libtorrent/write_resume_data.hpp>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <poll.h>
#include <unistd.h>
#include <atomic>
#include <cstring>
#include <filesystem>
#include <future>
#include <set>
#include <thread>

namespace fx = dtv::fixtures;
using namespace std::chrono_literals;
namespace {
dtv::engine_config config(std::string const& path) {
    dtv::engine_config cfg; cfg.cache_dir = path; cfg.cache_bytes = (4 * 256 + 64) << 10;
    cfg.reserve_bytes = 0; cfg.listen_interfaces = "127.0.0.1:0";
    cfg.dht = cfg.lsd = cfg.upnp = cfg.natpmp = cfg.pex = false;
    if (std::getenv("DTV_TEST_LOG")) cfg.log = [](std::string const& line) { std::printf("peer log: %s\n", line.c_str()); };
    cfg.multiple_connections_per_ip = true; cfg.read_ahead_seconds = 1; return cfg;
}
// A real loopback BitTorrent peer that supplies one block per piece and withholds the others.
// This deterministically leaves obsolete partial pieces after seeks, without corrupting bytes or hashes.
class partial_seeder {
    int listener = -1;
    std::atomic<int> client{-1};
    std::atomic<bool> stop{false};
    std::thread thread;
    int port = 0;
    bool io(int fd, char* bytes, int length, bool write) {
        int offset = 0;
        while (!stop && offset < length) {
            pollfd p{fd, short(write ? POLLOUT : POLLIN), 0};
            int ready = ::poll(&p, 1, 100); if (ready < 0) return false; if (!ready) continue;
            int send_flags = 0;
#ifdef MSG_NOSIGNAL
            send_flags = MSG_NOSIGNAL;
#endif
            auto n = write ? ::send(fd, bytes + offset, std::size_t(length - offset), send_flags)
                           : ::recv(fd, bytes + offset, std::size_t(length - offset), 0);
            if (n <= 0) return false;
            offset += int(n);
        }
        return offset == length;
    }
    static void integer(std::vector<char>& out, std::uint32_t n) { n = htonl(n); auto b = reinterpret_cast<char*>(&n); out.insert(out.end(), b, b + 4); }
    static std::uint32_t integer(char const* in) { std::uint32_t n; std::memcpy(&n, in, 4); return ntohl(n); }
    void serve(int pieces, int piece_length) {
        pollfd p{listener, POLLIN, 0};
        while (!stop && ::poll(&p, 1, 100) <= 0) {}
        if (stop) return;
        int fd = ::accept(listener, nullptr, nullptr); if (fd < 0) return; client = fd;
#ifdef SO_NOSIGPIPE
        int one = 1; ::setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof(one));
#endif
        char handshake[68]; if (!io(fd, handshake, 68, false)) return;
        std::memset(handshake + 20, 0, 8);
        std::string peer_id = "-DTV0001-000000000000", unique = std::to_string(port);
        peer_id.replace(peer_id.size() - unique.size(), unique.size(), unique); std::memcpy(handshake + 48, peer_id.data(), 20);
        if (!io(fd, handshake, 68, true)) return;
        std::vector<char> bits; integer(bits, std::uint32_t(1 + (pieces + 7) / 8)); bits.push_back(5);
        for (int i = 0; i < (pieces + 7) / 8; ++i) bits.push_back(char(i + 1 == (pieces + 7) / 8 && pieces % 8 ? 0xff << (8 - pieces % 8) : 0xff));
        integer(bits, 1); bits.push_back(1); if (!io(fd, bits.data(), int(bits.size()), true)) return;
        std::set<int> sent;
        while (!stop) {
            char header[4]; if (!io(fd, header, 4, false)) return;
            int length = int(integer(header)); if (length == 0) continue; if (length < 0 || length > 65536) return;
            std::vector<char> message(std::size_t(length), '\0'); if (!io(fd, message.data(), length, false)) return;
            if (length != 13 || message[0] != 6) continue;
            int piece = int(integer(message.data() + 1)), offset = int(integer(message.data() + 5)), size = int(integer(message.data() + 9));
            if (piece < 0 || piece >= pieces || offset < 0 || size <= 0 || size > 16384 || !sent.insert(piece).second) continue;
            if (std::getenv("DTV_TEST_LOG")) std::printf("peer %d sends piece %d block %d\n", port, piece, offset);
            std::vector<char> response; integer(response, std::uint32_t(9 + size)); response.push_back(7);
            integer(response, std::uint32_t(piece)); integer(response, std::uint32_t(offset));
            response.resize(std::size_t(13 + size)); fx::fill_content(41, std::int64_t(piece) * piece_length + offset, response.data() + 13, std::size_t(size));
            if (!io(fd, response.data(), int(response.size()), true)) return;
        }
    }
public:
    partial_seeder(int pieces, int piece_length) {
        listener = ::socket(AF_INET, SOCK_STREAM, 0); CHECK(listener >= 0);
        sockaddr_in address{}; address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        CHECK_EQ(::bind(listener, reinterpret_cast<sockaddr*>(&address), sizeof(address)), 0);
        socklen_t length = sizeof(address); CHECK_EQ(::getsockname(listener, reinterpret_cast<sockaddr*>(&address), &length), 0);
        port = ntohs(address.sin_port); CHECK_EQ(::listen(listener, 1), 0);
        thread = std::thread([this, pieces, piece_length] { serve(pieces, piece_length); });
    }
    ~partial_seeder() { stop = true; ::shutdown(listener, SHUT_RDWR); if (client >= 0) ::shutdown(client, SHUT_RDWR); thread.join(); if (client >= 0) ::close(client); ::close(listener); }
    std::string address() const { return "127.0.0.1:" + std::to_string(port); }
};
}

TEST(startup_tracker_fallback_preserves_embedded_and_private_sources) {
    fx::temp_dir dir("dtv-trackers"); fx::write_content(dir.file("movie.bin"), 2 << 20, 41);
    auto original = fx::make_torrent(dir.file("movie.bin"), 256 << 10, fx::version::v1);
    lt::entry entry(lt::bdecode({original.data(), std::ptrdiff_t(original.size())}));
    entry["announce"] = "http://127.0.0.1:9/embedded"; entry["info"]["private"] = 1;
    std::string private_torrent; lt::bencode(std::back_inserter(private_torrent), entry);
    dtv::engine engine(config(dir.file("cache")));
    dtv::source source; source.metainfo = private_torrent; source.fallback_trackers = {"http://127.0.0.1:9/fallback"};
    auto id = engine.add(source); engine.wait_metadata(id, 2s);
    auto bytes = engine.metainfo(id); auto loaded = lt::load_torrent_buffer({bytes.data(), std::ptrdiff_t(bytes.size())});
    CHECK(loaded.ti->priv()); CHECK_EQ(loaded.trackers.size(), std::size_t(1)); CHECK_EQ(loaded.trackers[0], std::string("http://127.0.0.1:9/embedded"));
    engine.remove(id);
    source = {}; source.magnet = "magnet:?xt=urn:btih:1111111111111111111111111111111111111111&tr=http%3A%2F%2F127.0.0.1%3A9%2Fembedded";
    source.fallback_trackers = {"http://127.0.0.1:9/fallback"};
    id = engine.add(source); engine.remove(id);
}

TEST(startup_partial_piece_pressure_recovers_without_hash_bypass_or_extra_cache) {
    fx::temp_dir dir("dtv-partials"); fx::write_content(dir.file("movie.bin"), 8 << 20, 41);
    auto torrent = fx::make_torrent(dir.file("movie.bin"), 256 << 10, fx::version::v1);
    std::vector<std::unique_ptr<partial_seeder>> stalled;
    stalled.push_back(std::make_unique<partial_seeder>(32, 256 << 10));
    dtv::engine engine(config(dir.file("cache")));
    dtv::source source; source.metainfo = torrent; source.peers = {stalled.back()->address()};
    auto id = engine.add(source); engine.wait_metadata(id, 2s); engine.select_file(id, 0); auto reader = engine.open_reader(id, 0);
    bool pressure = false; char byte;
    for (int seek = 0; seek < 6 && !pressure; ++seek) {
        if (std::getenv("DTV_TEST_LOG")) std::printf("seek %d\n", seek);
        if (seek > 0) { stalled.push_back(std::make_unique<partial_seeder>(32, 256 << 10)); engine.add_peers(id, {stalled.back()->address()}); }
        auto before = engine.stats(id).total_download;
        auto until = std::chrono::steady_clock::now() + 10s;
        while (std::chrono::steady_clock::now() < until) {
            CHECK_EQ(engine.read(reader, std::int64_t(seek * 5) * (256 << 10), &byte, 1, 20ms), dtv::engine::would_block);
            auto stats = engine.stats(id); pressure = stats.urgent_reservation_failures > 0 && stats.obsolete_partials > 0;
            if (pressure || stats.total_download > before) break;
        }
    }
    auto stalled_stats = engine.stats(id);
    if (!pressure) std::printf("pressure fixture: peers=%d bytes=%lld used=%d verified=%d obsolete=%d urgent=%d\n",
        stalled_stats.peers, static_cast<long long>(stalled_stats.total_download), stalled_stats.cache.used, stalled_stats.cache.verified,
        stalled_stats.obsolete_partials, stalled_stats.urgent_reservation_failures);
    CHECK(pressure); CHECK_EQ(engine.stats(id).hash_failures, 0);
    auto metadata = engine.metainfo(id); engine.remove(id);
    CHECK(std::filesystem::is_empty(dir.file("cache")));
    fx::seeder healthy(torrent, dir.path()); source = {}; source.metainfo = metadata; source.peers = {healthy.address()};
    id = engine.add(source); engine.wait_metadata(id, 2s); engine.select_file(id, 0); engine.set_demand(id, 4'000'000, 1); reader = engine.open_reader(id, 0);
    auto until = std::chrono::steady_clock::now() + 10s; int count;
    do { count = engine.read(reader, 6 << 20, &byte, 1, 200ms); CHECK(std::chrono::steady_clock::now() < until); } while (count == dtv::engine::would_block);
    CHECK_EQ(count, 1); CHECK_EQ(fx::first_mismatch(41, 6 << 20, &byte, 1), -1);
    CHECK_EQ(engine.stats(id).verified_read_bytes, 1); CHECK_EQ(engine.stats(id).hash_failures, 0);
}

TEST(startup_shutdown_wakes_a_native_read_before_its_timeout) {
    fx::temp_dir dir("dtv-close"); fx::write_content(dir.file("movie.bin"), 2 << 20, 41);
    auto torrent = fx::make_torrent(dir.file("movie.bin"), 256 << 10, fx::version::v1);
    dtv::engine engine(config(dir.file("cache"))); dtv::source source; source.metainfo = torrent;
    auto id = engine.add(source); engine.wait_metadata(id, 2s); engine.select_file(id, 0); auto reader = engine.open_reader(id, 0);
    auto blocked = std::async(std::launch::async, [&] { char byte; try { engine.read(reader, 0, &byte, 1, 30s); return false; } catch (dtv::engine_error const&) { return true; } });
    std::this_thread::sleep_for(100ms); engine.request_stop();
    CHECK(blocked.wait_for(1s) == std::future_status::ready); CHECK(blocked.get());
}
