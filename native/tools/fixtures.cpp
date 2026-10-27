#include "fixtures.hpp"

#include <libtorrent/add_torrent_params.hpp>
#include <libtorrent/create_torrent.hpp>
#include <libtorrent/load_torrent.hpp>
#include <libtorrent/settings_pack.hpp>

#include <chrono>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <filesystem>
#include <stdexcept>
#include <thread>
#include <unistd.h>
#include <vector>

namespace dtv::fixtures {

namespace {

std::uint64_t mix(std::uint64_t x)
{
	// splitmix64: a fast, well-distributed function of the position.
	x += 0x9e3779b97f4a7c15ULL;
	x = (x ^ (x >> 30)) * 0xbf58476d1ce4e5b9ULL;
	x = (x ^ (x >> 27)) * 0x94d049bb133111ebULL;
	return x ^ (x >> 31);
}

} // namespace

temp_dir::temp_dir(std::string const& prefix)
{
	char const* base = std::getenv("TMPDIR");
	std::string pattern = std::string(base && *base ? base : "/tmp") + "/" + prefix + "-XXXXXX";
	std::vector<char> buf(pattern.begin(), pattern.end());
	buf.push_back('\0');
	if (!::mkdtemp(buf.data())) throw std::runtime_error("mkdtemp failed");
	m_path = buf.data();
}

temp_dir::~temp_dir()
{
	std::error_code ec;
	std::filesystem::remove_all(m_path, ec);
}

void fill_content(std::uint32_t const seed, std::int64_t const offset, char* const out, std::size_t const size)
{
	for (std::size_t i = 0; i < size; ++i)
	{
		std::uint64_t const pos = std::uint64_t(offset) + i;
		std::uint64_t const word = mix((std::uint64_t(seed) << 40) ^ (pos >> 3));
		out[i] = char((word >> ((pos & 7) * 8)) & 0xff);
	}
}

std::int64_t first_mismatch(std::uint32_t const seed, std::int64_t const offset, char const* const data
	, std::size_t const size)
{
	std::vector<char> expected(size);
	fill_content(seed, offset, expected.data(), size);
	for (std::size_t i = 0; i < size; ++i)
		if (expected[i] != data[i]) return std::int64_t(i);
	return -1;
}

void write_content(std::string const& path, std::int64_t const size, std::uint32_t const seed, std::int64_t const hole)
{
	int const fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
	if (fd < 0) throw std::runtime_error("open " + path);
	// Zeros before `hole`, content from there on; only the content is written.
	if (::ftruncate(fd, off_t(size)) != 0) { ::close(fd); throw std::runtime_error("ftruncate " + path); }
	std::vector<char> buf(1 << 20);
	for (std::int64_t pos = hole; pos < size;)
	{
		std::size_t const n = std::size_t(std::min<std::int64_t>(std::int64_t(buf.size()), size - pos));
		fill_content(seed, pos, buf.data(), n);
		if (::pwrite(fd, buf.data(), n, off_t(pos)) != ssize_t(n)) { ::close(fd); throw std::runtime_error("write"); }
		pos += std::int64_t(n);
	}
	::close(fd);
}

std::string make_torrent(std::string const& path, int const piece_size, version const v)
{
	lt::create_flags_t flags = lt::create_torrent::no_attributes;
	if (v == version::v1) flags |= lt::create_torrent::v1_only;
	if (v == version::v2) flags |= lt::create_torrent::v2_only;
	lt::create_torrent t(lt::list_files(path, flags), piece_size, flags);
	lt::set_piece_hashes(t, std::filesystem::path(path).parent_path().string());
	std::vector<char> const buf = t.generate_buf();
	return std::string(buf.begin(), buf.end());
}

seeder::seeder(std::string const& torrent, std::string const& save_path, seeder_options const& opts)
{
	lt::settings_pack sp;
	sp.set_str(lt::settings_pack::listen_interfaces, opts.listen);
	sp.set_bool(lt::settings_pack::enable_dht, false);
	sp.set_str(lt::settings_pack::dht_bootstrap_nodes, "");
	sp.set_bool(lt::settings_pack::enable_lsd, false);
	sp.set_bool(lt::settings_pack::enable_upnp, false);
	sp.set_bool(lt::settings_pack::enable_natpmp, false);
	sp.set_bool(lt::settings_pack::allow_multiple_connections_per_ip, true);
	sp.set_int(lt::settings_pack::upload_rate_limit, opts.upload_rate);
	sp.set_bool(lt::settings_pack::disable_hash_checks, !opts.verify);
	sp.set_int(lt::settings_pack::alert_mask, lt::alert_category::error);
	m_session = std::make_unique<lt::session>(lt::session_params(std::move(sp)));

	lt::add_torrent_params p = lt::load_torrent_buffer({torrent.data(), std::ptrdiff_t(torrent.size())});
	p.save_path = save_path;
	p.flags &= ~(lt::torrent_flags::auto_managed | lt::torrent_flags::paused);
	if (opts.seed_mode) p.flags |= lt::torrent_flags::seed_mode;
	p.flags |= lt::torrent_flags::disable_dht | lt::torrent_flags::disable_lsd | lt::torrent_flags::disable_pex;
	m_handle = m_session->add_torrent(std::move(p));

	for (int i = 0; i < 200 && m_port == 0; ++i)
	{
		m_port = m_session->listen_port();
		if (m_port == 0) std::this_thread::sleep_for(std::chrono::milliseconds(10));
	}
	if (m_port == 0) throw std::runtime_error("The seeder could not listen on " + opts.listen);
}

seeder::~seeder() = default;

} // namespace dtv::fixtures
