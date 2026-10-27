#pragma once

#include <libtorrent/address.hpp>
#include <libtorrent/ip_filter.hpp>

namespace dtv {

// Optional live validation may use internet peers, but must never contact loopback or the owner's LAN.
// This filter also applies to tracker addresses and DHT nodes. Ordinary application sessions keep their
// configured networking; deterministic tests continue using explicitly supplied loopback peers.
inline lt::ip_filter public_network_filter()
{
	lt::ip_filter filter;
	auto block = [&](char const* first, char const* last) {
		filter.add_rule(lt::make_address(first), lt::make_address(last), lt::ip_filter::blocked);
	};
	block("0.0.0.0", "0.255.255.255");
	block("10.0.0.0", "10.255.255.255");
	block("100.64.0.0", "100.127.255.255");
	block("127.0.0.0", "127.255.255.255");
	block("169.254.0.0", "169.254.255.255");
	block("172.16.0.0", "172.31.255.255");
	block("192.0.0.0", "192.0.0.255");
	block("192.0.2.0", "192.0.2.255");
	block("192.168.0.0", "192.168.255.255");
	block("198.18.0.0", "198.19.255.255");
	block("198.51.100.0", "198.51.100.255");
	block("203.0.113.0", "203.0.113.255");
	block("224.0.0.0", "255.255.255.255");
	// Only global IPv6 unicast is eligible; mapped IPv4, ULA, link-local and multicast stay blocked.
	block("::", "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff");
	filter.add_rule(lt::make_address("2000::"), lt::make_address("3fff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"), 0);
	block("2001:db8::", "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff");
	return filter;
}

} // namespace dtv
