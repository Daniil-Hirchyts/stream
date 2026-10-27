#pragma once

// A minimal test runner: TEST(name) registers a test, CHECK fails it with the location.

#include <functional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace dtv::test {

struct failure : std::runtime_error
{
	using std::runtime_error::runtime_error;
};

struct entry
{
	char const* name;
	std::function<void()> run;
};

std::vector<entry>& registry();

struct registrar
{
	registrar(char const* name, std::function<void()> run) { registry().push_back({name, std::move(run)}); }
};

[[noreturn]] inline void fail(char const* file, int line, std::string const& what)
{
	std::ostringstream out;
	out << file << ":" << line << ": " << what;
	throw failure(out.str());
}

} // namespace dtv::test

#define TEST(name) \
	static void name(); \
	static ::dtv::test::registrar name##_registrar(#name, name); \
	static void name()

#define CHECK(cond) \
	do { if (!(cond)) ::dtv::test::fail(__FILE__, __LINE__, "CHECK(" #cond ")"); } while (false)

#define CHECK_EQ(a, b) \
	do { \
		auto const check_a_ = (a); \
		auto const check_b_ = (b); \
		if (!(check_a_ == check_b_)) \
		{ \
			std::ostringstream check_out_; \
			check_out_ << "CHECK_EQ(" #a ", " #b "): " << check_a_ << " != " << check_b_; \
			::dtv::test::fail(__FILE__, __LINE__, check_out_.str()); \
		} \
	} while (false)

// Runs `expr` and checks it throws engine_error of the given kind.
#define CHECK_THROWS_KIND(expr, k) \
	do { \
		bool check_thrown_ = false; \
		try { (void)(expr); } \
		catch (::dtv::engine_error const& check_e_) \
		{ \
			check_thrown_ = true; \
			if (check_e_.kind != (k)) \
				::dtv::test::fail(__FILE__, __LINE__, std::string("wrong error: ") + check_e_.what()); \
		} \
		if (!check_thrown_) ::dtv::test::fail(__FILE__, __LINE__, "no error from " #expr); \
	} while (false)
