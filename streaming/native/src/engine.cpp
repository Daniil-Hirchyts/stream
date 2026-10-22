#include "engine.hpp"
#include "network_policy.hpp"

#include <libtorrent/add_torrent_params.hpp>
#include <libtorrent/alert_types.hpp>
#include <libtorrent/download_priority.hpp>
#include <libtorrent/hex.hpp>
#include <libtorrent/load_torrent.hpp>
#include <libtorrent/magnet_uri.hpp>
#include <libtorrent/torrent_info.hpp>
#include <libtorrent/torrent_status.hpp>
#include <libtorrent/version.hpp>

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <filesystem>
#include <sys/statvfs.h>

namespace dtv {

namespace {

using clock = std::chrono::steady_clock;

std::int64_t now_ms()
{
	return std::chrono::duration_cast<std::chrono::milliseconds>(clock::now().time_since_epoch()).count();
}

// Deletes cache files a previous process left. Only *.cache files directly in the directory are touched.
void clear_cache_files(std::string const& dir)
{
	std::error_code ec;
	std::filesystem::create_directories(dir, ec);
	for (auto const& entry : std::filesystem::directory_iterator(dir, ec))
	{
		if (entry.is_regular_file(ec) && entry.path().extension() == ".cache") std::filesystem::remove(entry.path(), ec);
	}
}

lt::tcp::endpoint parse_peer(std::string const& text)
{
	auto const colon = text.rfind(':');
	if (colon == std::string::npos) throw engine_error(error_kind::bad_source, "Peer without a port: " + text);
	std::string host = text.substr(0, colon);
	if (host.size() >= 2 && host.front() == '[' && host.back() == ']') host = host.substr(1, host.size() - 2);
	lt::error_code ec;
	auto const address = lt::make_address(host, ec);
	int const port = std::atoi(text.c_str() + colon + 1);
	if (ec || port <= 0 || port > 65535) throw engine_error(error_kind::bad_source, "Invalid peer: " + text);
	return {address, std::uint16_t(port)};
}

lt::info_hash_t parse_info_hash(std::string const& hex)
{
	lt::info_hash_t ret;
	if (hex.size() == 40 && lt::aux::from_hex(hex, ret.v1.data())) return ret;
	if (hex.size() == 64 && lt::aux::from_hex(hex, ret.v2.data())) return ret;
	throw engine_error(error_kind::bad_source, "An info hash has 40 or 64 hexadecimal digits");
}

std::string mib(std::int64_t bytes)
{
	return std::to_string((bytes + (1 << 20) - 1) >> 20) + " MiB";
}

} // namespace

engine::engine(engine_config cfg)
	: m_config(std::move(cfg))
{
	if (m_config.cache_dir.empty() || m_config.cache_dir == "/")
		throw engine_error(error_kind::engine_failed, "No private cache directory");
	clear_cache_files(m_config.cache_dir);

	struct statvfs fs{};
	if (::statvfs(m_config.cache_dir.c_str(), &fs) == 0)
	{
		std::int64_t const available = std::int64_t(fs.f_bavail) * std::int64_t(fs.f_frsize);
		std::int64_t const needed = m_config.cache_bytes + m_config.reserve_bytes;
		if (available < needed)
		{
			throw engine_error(error_kind::low_storage, "Streaming needs " + mib(needed) + " of free storage ("
				+ mib(m_config.cache_bytes) + " cache and " + mib(m_config.reserve_bytes) + " to spare); "
				+ mib(available) + " is free. Free up space or choose a smaller cache.");
		}
	}

	// libtorrent's HTTPS client context loads the default verify paths, which honour SSL_CERT_FILE. Set before
	// the session exists, so before any thread of ours reads the environment.
	if (!m_config.ca_file.empty()) ::setenv("SSL_CERT_FILE", m_config.ca_file.c_str(), 1);

	lt::settings_pack sp;
	lt::alert_category_t mask = lt::alert_category::error | lt::alert_category::status
		| lt::alert_category::piece_progress | lt::alert_category::storage;
	if (m_config.log) mask |= lt::alert_category::peer;
	sp.set_int(lt::settings_pack::alert_mask, mask);
	// A streaming torrent counts as finished whenever its window is complete. Sending upload-only then would make
	// seeds disconnect, just before the window moves on and needs them again.
	sp.set_bool(lt::settings_pack::close_redundant_connections, false);
	sp.set_int(lt::settings_pack::connections_limit, m_config.connections_limit);
	sp.set_str(lt::settings_pack::listen_interfaces, m_config.listen_interfaces);
	sp.set_bool(lt::settings_pack::enable_dht, m_config.dht);
	if (!m_config.dht) sp.set_str(lt::settings_pack::dht_bootstrap_nodes, "");
	sp.set_bool(lt::settings_pack::enable_lsd, m_config.lsd && !m_config.public_network_only);
	sp.set_bool(lt::settings_pack::enable_upnp, m_config.upnp && !m_config.public_network_only);
	sp.set_bool(lt::settings_pack::enable_natpmp, m_config.natpmp && !m_config.public_network_only);
	sp.set_bool(lt::settings_pack::apply_ip_filter_to_trackers, m_config.public_network_only);
	sp.set_bool(lt::settings_pack::allow_multiple_connections_per_ip, m_config.multiple_connections_per_ip);
	sp.set_bool(lt::settings_pack::validate_https_trackers, true);
	sp.set_str(lt::settings_pack::user_agent, m_config.user_agent);
	sp.set_int(lt::settings_pack::max_metadata_size, 8 << 20);
	// Seeds drop a peer that has every piece. When an eviction makes this torrent download again, those seeds
	// must be reachable in seconds, not after the default minute.
	sp.set_int(lt::settings_pack::min_reconnect_time, 5);

	lt::session_params params(std::move(sp));
	if (m_config.public_network_only) params.ip_filter = public_network_filter();
	cache_config cc;
	cc.directory = m_config.cache_dir;
	cc.cache_bytes = m_config.cache_bytes;
	params.disk_io_constructor = cache_disk_io_constructor(cc, [this](cache_disk_io* d) { m_disk = d; });
	m_session = std::make_unique<lt::session>(std::move(params));
	// Called on libtorrent's network thread: it only signals, and takes no lock that thread could wait on.
	m_session->set_alert_notify([this] { wake(); });
	m_thread = std::thread([this] { loop(); });
}

engine::~engine()
{
	{
		std::lock_guard<std::mutex> lock(m_wake_mutex);
		m_stop = true;
	}
	m_wake_cv.notify_all();
	if (m_thread.joinable()) m_thread.join();
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		for (auto& [id, r] : m_readers) r->cancelled = true;
		for (auto& [id, t] : m_streams)
		{
			t->failed = true;
			t->error_type = error_kind::closed;
			t->changed.notify_all();
			if (t->cache) t->cache->close();
		}
	}
	m_session.reset();
}

std::string engine::version()
{
	return std::string("libtorrent ") + LIBTORRENT_VERSION + " with Daniil TV piece eviction";
}

void engine::wake()
{
	{
		std::lock_guard<std::mutex> lock(m_wake_mutex);
		m_woken = true;
	}
	m_wake_cv.notify_one();
}

std::shared_ptr<engine::stream> engine::find_stream(std::uint64_t const id)
{
	std::lock_guard<std::mutex> lock(m_mutex);
	auto const i = m_streams.find(id);
	if (i == m_streams.end()) throw engine_error(error_kind::invalid_handle, "No torrent with this handle");
	return i->second;
}

std::shared_ptr<engine::reader> engine::find_reader(std::uint64_t const id)
{
	std::lock_guard<std::mutex> lock(m_mutex);
	auto const i = m_readers.find(id);
	if (i == m_readers.end()) throw engine_error(error_kind::invalid_handle, "No reader with this handle");
	return i->second;
}

std::uint64_t engine::add(source const& src)
{
	lt::add_torrent_params p;
	try
	{
		if (!src.metainfo.empty())
		{
			// Bounded parsing: size, pieces, nesting and tokens all have limits.
			lt::load_torrent_limits limits;
			limits.max_buffer_size = 8 << 20;
			p = lt::load_torrent_buffer({src.metainfo.data(), std::ptrdiff_t(src.metainfo.size())}, limits);
		}
		else if (!src.magnet.empty())
		{
			p = lt::parse_magnet_uri(src.magnet);
		}
		else if (!src.info_hash.empty())
		{
			p.info_hashes = parse_info_hash(src.info_hash);
		}
		else
		{
			throw engine_error(error_kind::bad_source, "No torrent, magnet link or info hash");
		}
	}
	catch (engine_error const&)
	{
		throw;
	}
	catch (std::exception const& e)
	{
		// libtorrent reports parse errors as boost::system::system_error.
		throw engine_error(error_kind::bad_source, std::string("Invalid torrent: ") + e.what());
	}
	if (p.ti)
	{
		int const piece_length = p.ti->piece_length();
		if (piece_length > m_config.max_piece_bytes)
		{
			throw engine_error(error_kind::piece_too_large, "This torrent uses " + mib(piece_length)
				+ " pieces; streaming supports up to " + mib(m_config.max_piece_bytes) + ". Choose another source.");
		}
		if (cache_slots(m_config.cache_bytes, piece_length) < m_config.min_slots)
		{
			throw engine_error(error_kind::cache_too_small, "This torrent's " + mib(piece_length)
				+ " pieces need a cache of at least " + mib(std::int64_t(piece_length) * m_config.min_slots + cache_storage_overhead) + ".");
		}
	}
	for (auto const& url : src.trackers) p.trackers.push_back(url);
	for (auto const& peer : src.peers) p.peers.push_back(parse_peer(peer));
	p.save_path = m_config.cache_dir;
	// Nothing downloads until a file is chosen, and then only the read-ahead window.
	p.flags |= lt::torrent_flags::default_dont_download;
	p.flags &= ~(lt::torrent_flags::auto_managed | lt::torrent_flags::paused);
	if (!m_config.dht) p.flags |= lt::torrent_flags::disable_dht;
	if (!m_config.lsd) p.flags |= lt::torrent_flags::disable_lsd;
	if (!m_config.pex) p.flags |= lt::torrent_flags::disable_pex;
	bool const has_metadata = bool(p.ti);

	lt::error_code ec;
	lt::torrent_handle h = m_session->add_torrent(std::move(p), ec);
	if (ec) throw engine_error(error_kind::bad_source, ec.message());

	auto t = std::make_shared<stream>();
	t->handle = h;
	t->bitrate = m_config.default_bitrate;
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		t->id = m_next_id++;
		m_streams[t->id] = t;
	}
	if (has_metadata) on_metadata(t);
	wake();
	return t->id;
}

void engine::on_metadata(std::shared_ptr<stream> const& t)
{
	std::shared_ptr<lt::torrent_info const> info;
	try { info = t->handle.torrent_file(); }
	catch (std::exception const&) { return; }
	if (!info) return;
	int const piece_length = info->piece_length();
	if (piece_length > m_config.max_piece_bytes)
	{
		fail(*t, error_kind::piece_too_large, "This torrent uses " + mib(piece_length)
			+ " pieces; streaming supports up to " + mib(m_config.max_piece_bytes) + ". Choose another source.");
		return;
	}
	if (cache_slots(m_config.cache_bytes, piece_length) < m_config.min_slots)
	{
		fail(*t, error_kind::cache_too_small, "This torrent's " + mib(piece_length)
			+ " pieces need a cache of at least " + mib(std::int64_t(piece_length) * m_config.min_slots + cache_storage_overhead) + ".");
		return;
	}
	std::lock_guard<std::mutex> lock(m_mutex);
	if (t->info) return;
	t->info = info;
	t->info_hash = info->info_hashes().get_best();
	t->changed.notify_all();
}

void engine::fail(stream& t, error_kind const kind, std::string const& message)
{
	std::shared_ptr<piece_cache> cache;
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		if (t.failed) return;
		t.failed = true;
		t.error = message;
		t.error_type = kind;
		cache = t.cache;
		t.changed.notify_all();
	}
	if (cache) cache->wake_all();
	try { t.handle.pause(); }
	catch (std::exception const&) {}
}

std::vector<file_info> engine::wait_metadata(std::uint64_t const torrent, std::chrono::milliseconds const timeout
	, std::atomic<bool> const* cancelled)
{
	auto t = find_stream(torrent);
	auto const deadline = clock::now() + timeout;
	std::shared_ptr<lt::torrent_info const> info;
	{
		std::unique_lock<std::mutex> lock(m_mutex);
		while (!t->info && !t->failed)
		{
			if (cancelled && cancelled->load()) throw engine_error(error_kind::cancelled, "Cancelled");
			auto const now = clock::now();
			if (now >= deadline) throw engine_error(error_kind::timeout, "No peer sent the torrent's metadata in time");
			// Short waits, so a cancellation is noticed.
			t->changed.wait_until(lock, std::min(deadline, now + std::chrono::milliseconds(200)));
		}
		if (t->failed) throw engine_error(t->error_type, t->error);
		info = t->info;
	}
	std::vector<file_info> files;
	lt::file_storage const& fs = info->layout();
	for (lt::file_index_t f : fs.file_range())
	{
		if (fs.pad_file_at(f)) continue;
		files.push_back({static_cast<int>(f), fs.file_path(f), fs.file_size(f)});
	}
	return files;
}

void engine::select_file(std::uint64_t const torrent, int const file)
{
	auto t = find_stream(torrent);
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		if (t->failed) throw engine_error(t->error_type, t->error);
		if (!t->info) throw engine_error(error_kind::invalid_file, "The torrent's files aren't known yet");
		lt::file_storage const& fs = t->info->layout();
		// An explicit index must exist; it is never replaced by another file.
		if (file < 0 || file >= fs.num_files() || fs.pad_file_at(lt::file_index_t(file)))
			throw engine_error(error_kind::invalid_file, "The torrent has no file " + std::to_string(file));
		t->file = file;
		t->anchor = 0;
	}
	wake();
}

void engine::set_bitrate(std::uint64_t const torrent, std::int64_t const bytes_per_second)
{
	auto t = find_stream(torrent);
	std::lock_guard<std::mutex> lock(m_mutex);
	if (bytes_per_second > 0) t->bitrate = bytes_per_second;
}

void engine::add_peers(std::uint64_t const torrent, std::vector<std::string> const& peers)
{
	auto t = find_stream(torrent);
	std::vector<lt::tcp::endpoint> endpoints;
	for (auto const& peer : peers) endpoints.push_back(parse_peer(peer));
	for (auto const& ep : endpoints) t->handle.connect_peer(ep);
}

void engine::remove(std::uint64_t const torrent)
{
	std::shared_ptr<stream> t;
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		auto const i = m_streams.find(torrent);
		if (i == m_streams.end()) return;
		t = i->second;
		m_streams.erase(i);
		t->failed = true;
		t->error = "The torrent was closed";
		t->error_type = error_kind::closed;
		for (auto const& weak : t->readers)
			if (auto r = weak.lock()) r->cancelled = true;
		t->changed.notify_all();
		if (t->cache) t->cache->close();
	}
	try { m_session->remove_torrent(t->handle); }
	catch (std::exception const&) {}
}

torrent_stats engine::stats(std::uint64_t const torrent)
{
	auto t = find_stream(torrent);
	torrent_stats ret;
	std::shared_ptr<piece_cache> cache;
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		ret.state = t->failed ? "failed" : !t->info ? "metadata" : t->file < 0 ? "ready" : "streaming";
		ret.error = t->error;
		ret.error_kind = t->failed ? int(t->error_type) : 0;
		if (t->info)
		{
			ret.piece_length = t->info->piece_length();
			ret.num_pieces = t->info->num_pieces();
		}
		cache = t->cache;
	}
	ret.hash_failures = t->hash_failures;
	ret.evictions_requested = t->evictions;
	try
	{
		lt::torrent_status const st = t->handle.status();
		ret.peers = st.num_peers;
		ret.seeds = st.num_seeds;
		ret.download_rate = st.download_payload_rate;
		ret.upload_rate = st.upload_payload_rate;
		ret.total_download = st.total_payload_download;
		ret.total_upload = st.total_payload_upload;
	}
	catch (std::exception const&) {}
	if (cache) ret.cache = cache->get_stats();
	if (auto* disk = m_disk.load()) ret.disk = disk->get_stats();
	return ret;
}

std::uint64_t engine::open_reader(std::uint64_t const torrent, int const file)
{
	auto t = find_stream(torrent);
	std::lock_guard<std::mutex> lock(m_mutex);
	if (t->failed) throw engine_error(t->error_type, t->error);
	if (!t->info) throw engine_error(error_kind::invalid_file, "The torrent's files aren't known yet");
	lt::file_storage const& fs = t->info->layout();
	if (file < 0 || file >= fs.num_files() || fs.pad_file_at(lt::file_index_t(file)))
		throw engine_error(error_kind::invalid_file, "The torrent has no file " + std::to_string(file));
	auto r = std::make_shared<reader>();
	r->id = m_next_id++;
	r->torrent = t;
	r->file = file;
	r->last_read = now_ms();
	m_readers[r->id] = r;
	t->readers.push_back(r);
	return r->id;
}

std::int64_t engine::file_size(std::uint64_t const id)
{
	auto r = find_reader(id);
	auto t = r->torrent.lock();
	if (!t) throw engine_error(error_kind::closed, "The torrent was closed");
	std::lock_guard<std::mutex> lock(m_mutex);
	return t->info->layout().file_size(lt::file_index_t(r->file));
}

int engine::read(std::uint64_t const id, std::int64_t const position, char* const out, int const length
	, std::chrono::milliseconds const wait)
{
	auto r = find_reader(id);
	if (r->cancelled) throw engine_error(error_kind::cancelled, "The read was cancelled");
	auto t = r->torrent.lock();
	if (!t) throw engine_error(error_kind::closed, "The torrent was closed");
	if (position < 0 || length < 0) throw engine_error(error_kind::invalid_file, "Invalid read position");

	std::shared_ptr<piece_cache> cache;
	std::shared_ptr<lt::torrent_info const> info;
	{
		std::unique_lock<std::mutex> lock(m_mutex);
		if (t->failed) throw engine_error(t->error_type, t->error);
		info = t->info;
		cache = t->cache;
	}
	lt::file_storage const& fs = info->layout();
	lt::file_index_t const file(r->file);
	std::int64_t const size = fs.file_size(file);
	if (position >= size || length == 0) return 0;
	int const wanted = int(std::min<std::int64_t>(length, size - position));
	lt::peer_request const req = fs.map_file(file, position, wanted);
	int const chunk = std::min(wanted, fs.piece_size(req.piece) - req.start);

	r->position = position;
	r->last_read = now_ms();
	if (r->waiting.exchange(static_cast<int>(req.piece)) != static_cast<int>(req.piece)) wake();

	if (!cache)
	{
		// The torrent's storage appears right after its metadata; wait for the controller to find it.
		std::unique_lock<std::mutex> lock(m_mutex);
		t->changed.wait_for(lock, wait, [&] { return t->cache || t->failed || r->cancelled; });
		if (t->failed) throw engine_error(t->error_type, t->error);
		if (r->cancelled) throw engine_error(error_kind::cancelled, "The read was cancelled");
		return would_block;
	}

	switch (cache->wait_verified(req.piece, clock::now() + wait, r->cancelled))
	{
		case piece_cache::wait_result::cancelled: throw engine_error(error_kind::cancelled, "The read was cancelled");
		case piece_cache::wait_result::closed:
		{
			std::lock_guard<std::mutex> lock(m_mutex);
			if (t->failed) throw engine_error(t->error_type, t->error);
			throw engine_error(error_kind::closed, "The torrent was closed");
		}
		case piece_cache::wait_result::timeout:
		{
			std::lock_guard<std::mutex> lock(m_mutex);
			if (t->failed) throw engine_error(t->error_type, t->error);
			return would_block;
		}
		case piece_cache::wait_result::ready: break;
	}
	lt::storage_error error;
	// The piece may have been evicted since; the next call waits for it again.
	if (!cache->read_verified(req.piece, req.start, {out, chunk}, error)) return would_block;
	if (error) throw engine_error(error_kind::engine_failed, "Cache read failed: " + error.ec.message());
	r->waiting = -1;
	r->position = position + chunk;
	// The window follows the reader; moving into the next piece is worth an immediate update.
	if (req.start + chunk == fs.piece_size(req.piece)) wake();
	return chunk;
}

void engine::cancel(std::uint64_t const id)
{
	std::shared_ptr<reader> r;
	try { r = find_reader(id); }
	catch (engine_error const&) { return; }
	r->cancelled = true;
	if (auto t = r->torrent.lock())
	{
		std::shared_ptr<piece_cache> cache;
		{
			std::lock_guard<std::mutex> lock(m_mutex);
			cache = t->cache;
			t->changed.notify_all();
		}
		if (cache) cache->wake_all();
	}
}

void engine::close_reader(std::uint64_t const id)
{
	cancel(id);
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		m_readers.erase(id);
	}
	wake();
}

void engine::loop()
{
	auto next = clock::now();
	for (;;)
	{
		{
			std::unique_lock<std::mutex> lock(m_wake_mutex);
			m_wake_cv.wait_until(lock, next, [this] { return m_woken || m_stop; });
			if (m_stop) return;
			m_woken = false;
		}
		try
		{
			handle_alerts();
			std::vector<std::shared_ptr<stream>> streams;
			{
				std::lock_guard<std::mutex> lock(m_mutex);
				for (auto const& [id, t] : m_streams) streams.push_back(t);
			}
			for (auto const& t : streams) update(t);
		}
		catch (std::exception const&)
		{
			// A torrent removed mid-update makes its handle throw; the next pass no longer sees it.
		}
		next = clock::now() + std::chrono::milliseconds(100);
	}
}

void engine::handle_alerts()
{
	std::vector<lt::alert*> alerts;
	m_session->pop_alerts(&alerts);
	for (lt::alert* a : alerts)
	{
		if (m_config.log && (lt::alert_cast<lt::peer_disconnected_alert>(a) || lt::alert_cast<lt::peer_error_alert>(a)
			|| (a->category() & lt::alert_category::error)))
			m_config.log(a->message());
		auto* ta = dynamic_cast<lt::torrent_alert*>(a);
		if (!ta) continue;
		std::shared_ptr<stream> t;
		{
			std::lock_guard<std::mutex> lock(m_mutex);
			for (auto const& [id, s] : m_streams)
				if (s->handle == ta->handle) t = s;
		}
		if (!t) continue;

		if (lt::alert_cast<lt::metadata_received_alert>(a))
		{
			on_metadata(t);
		}
		else if (auto* pf = lt::alert_cast<lt::piece_finished_alert>(a))
		{
			std::shared_ptr<piece_cache> cache;
			{
				std::lock_guard<std::mutex> lock(m_mutex);
				if (!t->cache && t->info)
					if (auto* disk = m_disk.load()) t->cache = disk->find(t->info_hash);
				cache = t->cache;
				t->changed.notify_all();
			}
			if (cache) cache->set_verified(pf->piece_index);
		}
		else if (lt::alert_cast<lt::hash_failed_alert>(a))
		{
			++t->hash_failures;
		}
		else if (auto* me = lt::alert_cast<lt::metadata_failed_alert>(a))
		{
			fail(*t, error_kind::metadata_failed, "The torrent's metadata is invalid: " + me->error.message());
		}
		else if (auto* te = lt::alert_cast<lt::torrent_error_alert>(a))
		{
			fail(*t, error_kind::torrent_failed, te->error.message());
		}
		else if (auto* fe = lt::alert_cast<lt::file_error_alert>(a))
		{
			fail(*t, error_kind::torrent_failed, "Cache error: " + fe->error.message());
		}
	}
}

// The read-ahead controller. From the active reader's position it keeps a window of pieces covering roughly
// read_ahead_seconds of media, bounded by the cache: pieces a reader is waiting for come first and get deadlines,
// partial pieces outside the window finish at the lowest priority, and everything else stays undownloaded.
// Slots are reserved before a piece may download; when none is free, the least recently used verified pieces
// outside the window and its margins are evicted.
void engine::update(std::shared_ptr<stream> const& t)
{
	cache_disk_io* const disk = m_disk.load();
	std::shared_ptr<piece_cache> cache;
	std::shared_ptr<lt::torrent_info const> info;
	int file;
	std::int64_t bitrate;
	std::int64_t anchor;
	std::vector<int> waiting;
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		if (t->failed || !t->info || t->file < 0) return;
		if (!t->cache && disk)
		{
			t->cache = disk->find(t->info_hash);
			if (t->cache) t->changed.notify_all();
		}
		if (!t->cache) return;
		cache = t->cache;
		info = t->info;
		file = t->file;
		bitrate = t->bitrate;
		std::shared_ptr<reader> primary;
		for (auto i = t->readers.begin(); i != t->readers.end();)
		{
			auto r = i->lock();
			if (!r || r->cancelled) { i = t->readers.erase(i); continue; }
			if (r->file == file)
			{
				if (!primary || r->last_read > primary->last_read) primary = r;
				int const w = r->waiting;
				if (w >= 0) waiting.push_back(w);
			}
			++i;
		}
		if (primary) t->anchor = primary->position;
		anchor = t->anchor;
	}

	lt::file_storage const& fs = info->layout();
	lt::file_index_t const f(file);
	std::int64_t const size = fs.file_size(f);
	if (size <= 0) return;
	piece_index_t const first = fs.map_file(f, 0, 1).piece;
	piece_index_t const last = fs.map_file(f, size - 1, 1).piece;
	piece_index_t const head = fs.map_file(f, std::clamp<std::int64_t>(anchor, 0, size - 1), 1).piece;
	int const num_pieces = info->num_pieces();
	int const slots = cache->slot_count();
	// Large caches also keep the file's first and last pieces, where containers keep headers and indexes.
	bool const keep_edges = slots >= 8;
	int const margin = 1 /* free spare */ + 1 /* behind the reader */ + (keep_edges ? 2 : 0);
	int const max_ahead = std::max(1, slots - margin - int(waiting.size()));
	int const for_time = int(std::ceil(double(m_config.read_ahead_seconds) * double(bitrate) / fs.piece_length()));
	int const ahead = std::min(max_ahead, std::max(m_config.min_read_ahead_pieces, for_time));

	std::vector<bool> wanted(std::size_t(num_pieces), false);
	std::vector<bool> keep(std::size_t(num_pieces), false);
	std::vector<piece_index_t> desired;
	auto const add = [&](piece_index_t const p) {
		if (wanted[std::size_t(static_cast<int>(p))]) return;
		wanted[std::size_t(static_cast<int>(p))] = true;
		keep[std::size_t(static_cast<int>(p))] = true;
		desired.push_back(p);
	};
	for (int w : waiting) add(piece_index_t(w));
	for (int i = 0; i < ahead; ++i)
	{
		piece_index_t const p(static_cast<int>(head) + i);
		if (p > last) break;
		add(p);
	}
	// Waited-for pieces, the reader's piece and the next are urgent.
	std::size_t const urgent_count = waiting.size() + 2;
	if (head > first) keep[std::size_t(static_cast<int>(head) - 1)] = true;
	if (keep_edges)
	{
		keep[std::size_t(static_cast<int>(first))] = true;
		keep[std::size_t(static_cast<int>(last))] = true;
	}

	std::vector<lt::download_priority_t> prio(std::size_t(num_pieces), lt::dont_download);
	std::vector<piece_index_t> urgent;
	int need = 0;
	for (std::size_t i = 0; i < desired.size(); ++i)
	{
		piece_index_t const p = desired[i];
		if (cache->verified(p)) continue;
		if (!cache->reserve(p)) { ++need; continue; }
		bool const is_urgent = i < urgent_count;
		prio[std::size_t(static_cast<int>(p))] = is_urgent ? lt::top_priority : lt::default_priority;
		if (is_urgent) urgent.push_back(p);
	}
	for (auto const& [p, kind] : cache->pieces())
	{
		auto& pr = prio[std::size_t(static_cast<int>(p))];
		if (kind == piece_cache::kind::complete && t->handle.have_piece(p))
		{
			// A dropped piece_finished_alert must not leave a reader waiting.
			cache->set_verified(p);
			continue;
		}
		if (pr != lt::dont_download) continue;
		if (kind == piece_cache::kind::partial || kind == piece_cache::kind::complete)
			pr = lt::low_priority; // finish what was started; then it can be evicted
		else if (kind == piece_cache::kind::reserved && !keep[std::size_t(static_cast<int>(p))])
			cache->unreserve(p);
	}

	if (t->priorities_stale.exchange(false)) t->applied.clear();
	if (prio != t->applied)
	{
		t->handle.prioritize_pieces(prio);
		t->applied = prio;
	}
	if (urgent != t->deadlines)
	{
		// A seek leaves old deadlines behind; clear them so they don't compete with the new position.
		t->handle.clear_piece_deadlines();
		for (std::size_t i = 0; i < urgent.size(); ++i) t->handle.set_piece_deadline(urgent[i], int(i) * 500);
		t->deadlines = urgent;
	}

	int const blocked = disk ? disk->get_stats().blocked_writes : 0;
	int const missing = need + 1 + (blocked > 0 ? 1 : 0) - cache->free_slots();
	if (missing > 0 && !t->evicting)
	{
		auto candidates = cache->eviction_candidates(missing
			, [&keep](piece_index_t const p) { return bool(keep[std::size_t(static_cast<int>(p))]); });
		if (!candidates.empty()) evict(t, std::move(candidates));
	}
}

void engine::evict(std::shared_ptr<stream> const& t, std::vector<piece_index_t> pieces)
{
	std::shared_ptr<piece_cache> cache;
	{
		std::lock_guard<std::mutex> lock(m_mutex);
		cache = t->cache;
	}
	t->evicting = true;
	++t->evictions;
	cache->begin_release(pieces);
	std::weak_ptr<stream> weak = t;
	// The callback runs on libtorrent's network thread once the torrent no longer has the pieces. Disk jobs
	// for them queued before then still complete: the release is queued behind them.
	t->handle.evict_pieces(pieces, [this, weak, cache, pieces](std::vector<piece_index_t> evicted) {
		if (auto* disk = m_disk.load()) disk->release(cache, evicted);
		for (piece_index_t const p : pieces)
			if (std::find(evicted.begin(), evicted.end(), p) == evicted.end()) cache->cancel_release(p);
		if (auto s = weak.lock())
		{
			s->priorities_stale = true;
			s->evicting = false;
		}
		wake();
	});
}

} // namespace dtv
