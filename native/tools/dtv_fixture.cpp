// dtv-fixture: test fixtures for Stream, from the same native code as the app.
//
//   dtv-fixture content <path> <bytes> <seed>
//   dtv-fixture create <out.torrent> <file-or-dir> <piece-bytes> [v1|v2|hybrid]
//   dtv-fixture info <file.torrent>
//   dtv-fixture seed [--listen 127.0.0.1:58134] [--rate bytes/s] <file.torrent> <save-path> [...]
//
// `seed` runs a real BitTorrent seeder with DHT, local discovery, port mapping and trackers off, until SIGINT or
// SIGTERM; peers reach it only at the address given.

#include "fixtures.hpp"

#include <libtorrent/add_torrent_params.hpp>
#include <libtorrent/alert_types.hpp>
#include <libtorrent/hex.hpp>
#include <libtorrent/load_torrent.hpp>
#include <libtorrent/session.hpp>
#include <libtorrent/torrent_info.hpp>

#include <atomic>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <string>
#include <thread>
#include <vector>

namespace fx = dtv::fixtures;

namespace {

std::atomic<bool> stop{false};

std::string read_file(std::string const& path)
{
	std::ifstream in(path, std::ios::binary);
	if (!in) throw std::runtime_error("cannot read " + path);
	return std::string(std::istreambuf_iterator<char>(in), {});
}

int usage()
{
	std::cerr << "usage:\n"
		"  dtv-fixture content <path> <bytes> <seed>\n"
		"  dtv-fixture create <out.torrent> <file-or-dir> <piece-bytes> [v1|v2|hybrid]\n"
		"  dtv-fixture info <file.torrent>\n"
		"  dtv-fixture seed [--listen 127.0.0.1:58134] [--rate bytes/s] <file.torrent> <save-path> [...]\n";
	return 2;
}

int info(std::string const& path)
{
	std::string const data = read_file(path);
	auto const p = lt::load_torrent_buffer({data.data(), std::ptrdiff_t(data.size())});
	auto const& ti = *p.ti;
	auto const& ih = ti.info_hashes();
	std::cout << "{\"name\":\"" << ti.name() << "\",\"pieceLength\":" << ti.piece_length()
		<< ",\"pieces\":" << ti.num_pieces()
		<< ",\"v1\":\"" << (ih.has_v1() ? lt::aux::to_hex(ih.v1) : "") << "\""
		<< ",\"v2\":\"" << (ih.has_v2() ? lt::aux::to_hex(ih.v2) : "") << "\",\"files\":[";
	auto const& fs = ti.layout();
	bool first = true;
	for (lt::file_index_t f : fs.file_range())
	{
		if (fs.pad_file_at(f)) continue;
		std::cout << (first ? "" : ",") << "{\"index\":" << static_cast<int>(f) << ",\"path\":\"" << fs.file_path(f)
			<< "\",\"size\":" << fs.file_size(f) << "}";
		first = false;
	}
	std::cout << "]}\n";
	return 0;
}

int seed(std::vector<std::string> args)
{
	std::string listen = "127.0.0.1:58134";
	int rate = 0;
	std::vector<std::pair<std::string, std::string>> torrents;
	for (std::size_t i = 0; i < args.size(); ++i)
	{
		if (args[i] == "--listen" && i + 1 < args.size()) listen = args[++i];
		else if (args[i] == "--rate" && i + 1 < args.size()) rate = std::atoi(args[++i].c_str());
		else if (i + 1 < args.size()) { torrents.emplace_back(args[i], args[i + 1]); ++i; }
		else return usage();
	}
	if (torrents.empty()) return usage();

	lt::settings_pack sp;
	sp.set_str(lt::settings_pack::listen_interfaces, listen);
	sp.set_bool(lt::settings_pack::enable_dht, false);
	sp.set_str(lt::settings_pack::dht_bootstrap_nodes, "");
	sp.set_bool(lt::settings_pack::enable_lsd, false);
	sp.set_bool(lt::settings_pack::enable_upnp, false);
	sp.set_bool(lt::settings_pack::enable_natpmp, false);
	sp.set_bool(lt::settings_pack::allow_multiple_connections_per_ip, true);
	sp.set_int(lt::settings_pack::upload_rate_limit, rate);
	sp.set_int(lt::settings_pack::alert_mask, lt::alert_category::error | lt::alert_category::status);
	lt::session s{lt::session_params(std::move(sp))};
	for (auto const& [torrent, save] : torrents)
	{
		std::string const data = read_file(torrent);
		lt::add_torrent_params p = lt::load_torrent_buffer({data.data(), std::ptrdiff_t(data.size())});
		p.save_path = save;
		p.flags &= ~(lt::torrent_flags::auto_managed | lt::torrent_flags::paused);
		p.flags |= lt::torrent_flags::seed_mode | lt::torrent_flags::disable_dht | lt::torrent_flags::disable_lsd
			| lt::torrent_flags::disable_pex;
		auto const ih = p.ti->info_hashes();
		s.add_torrent(std::move(p));
		std::cout << "seeding " << (ih.has_v1() ? lt::aux::to_hex(ih.v1) : lt::aux::to_hex(ih.v2)) << " from " << save
			<< "\n";
	}
	int port = 0;
	for (int i = 0; i < 500 && port == 0; ++i)
	{
		port = s.listen_port();
		if (port == 0) std::this_thread::sleep_for(std::chrono::milliseconds(10));
	}
	if (port == 0) { std::cerr << "could not listen on " << listen << "\n"; return 1; }
	std::cout << "listening on port " << port << std::endl;

	std::signal(SIGINT, [](int) { stop = true; });
	std::signal(SIGTERM, [](int) { stop = true; });
	while (!stop)
	{
		std::vector<lt::alert*> alerts;
		s.pop_alerts(&alerts);
		for (lt::alert* a : alerts)
			if (a->category() & lt::alert_category::error) std::cerr << a->message() << "\n";
		s.wait_for_alert(std::chrono::milliseconds(200));
	}
	return 0;
}

} // namespace

int main(int argc, char* argv[]) try
{
	if (argc < 2) return usage();
	std::string const cmd = argv[1];
	if (cmd == "content" && argc == 5)
	{
		fx::write_content(argv[2], std::atoll(argv[3]), std::uint32_t(std::strtoul(argv[4], nullptr, 10)));
		return 0;
	}
	if (cmd == "create" && (argc == 5 || argc == 6))
	{
		std::string const v = argc == 6 ? argv[5] : "hybrid";
		fx::version const version = v == "v1" ? fx::version::v1 : v == "v2" ? fx::version::v2 : fx::version::hybrid;
		std::string const data = fx::make_torrent(argv[3], std::atoi(argv[4]), version);
		std::ofstream(argv[2], std::ios::binary).write(data.data(), std::streamsize(data.size()));
		return info(argv[2]);
	}
	if (cmd == "info" && argc == 3) return info(argv[2]);
	if (cmd == "seed") return seed(std::vector<std::string>(argv + 2, argv + argc));
	return usage();
}
catch (std::exception const& e)
{
	std::cerr << "dtv-fixture: " << e.what() << "\n";
	return 1;
}
