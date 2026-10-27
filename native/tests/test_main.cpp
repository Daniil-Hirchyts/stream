#include "test.hpp"

#include <chrono>
#include <cstdio>
#include <cstring>

namespace dtv::test {

std::vector<entry>& registry()
{
	static std::vector<entry> tests;
	return tests;
}

} // namespace dtv::test

// Runs every test, or those whose names contain an argument. Exits non-zero if any fails.
int main(int argc, char* argv[])
{
	int passed = 0, failed = 0;
	for (auto const& t : dtv::test::registry())
	{
		bool selected = argc < 2;
		for (int i = 1; i < argc; ++i) selected |= std::strstr(t.name, argv[i]) != nullptr;
		if (!selected) continue;
		auto const start = std::chrono::steady_clock::now();
		std::printf("RUN  %s\n", t.name);
		std::fflush(stdout);
		try
		{
			t.run();
			++passed;
			auto const ms = std::chrono::duration_cast<std::chrono::milliseconds>(
				std::chrono::steady_clock::now() - start).count();
			std::printf("PASS %s (%lld ms)\n", t.name, static_cast<long long>(ms));
		}
		catch (std::exception const& e)
		{
			++failed;
			std::printf("FAIL %s: %s\n", t.name, e.what());
		}
		std::fflush(stdout);
	}
	std::printf("%d passed, %d failed\n", passed, failed);
	return failed == 0 && passed > 0 ? 0 : 1;
}
