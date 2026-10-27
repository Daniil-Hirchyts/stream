// JNI entry points for tv.stream.torrent.TorrentEngine. Engines are reached through 64-bit ids that are never
// reused; every call holds a reference for its duration, so closing an engine while a read blocks in another
// thread can't free it underneath. Errors become TorrentException(kind, message) with dtv::error_kind's numbers.

#include "engine.hpp"

#include <jni.h>

#include <deque>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace {

struct engine_entry
{
	std::unique_ptr<dtv::engine> engine;
	std::mutex calls_mutex;
	std::condition_variable calls_changed;
	int calls = 0;
	// Recent diagnostics for the Developer view; native threads never call into Java.
	std::mutex log_mutex;
	std::deque<std::string> log;
};

std::mutex registry_mutex;
std::map<jlong, std::shared_ptr<engine_entry>> registry;
jlong next_engine_id = 1;

// NewStringUTF() takes modified UTF-8, which torrent file names (any UTF-8, emoji included) need not be.
jstring utf8_string(JNIEnv* env, std::string const& s)
{
	jbyteArray const bytes = env->NewByteArray(jsize(s.size()));
	if (!bytes) return nullptr;
	env->SetByteArrayRegion(bytes, 0, jsize(s.size()), reinterpret_cast<jbyte const*>(s.data()));
	jclass const cls = env->FindClass("java/lang/String");
	jmethodID const ctor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
	jstring const charset = env->NewStringUTF("UTF-8");
	auto const ret = static_cast<jstring>(env->NewObject(cls, ctor, bytes, charset));
	env->DeleteLocalRef(charset);
	env->DeleteLocalRef(cls);
	env->DeleteLocalRef(bytes);
	return ret;
}

void throw_error(JNIEnv* env, int kind, std::string const& message)
{
	if (env->ExceptionCheck()) return;
	jclass const cls = env->FindClass("tv/stream/torrent/TorrentException");
	if (!cls) return;
	jmethodID const ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;)V");
	jstring const text = utf8_string(env, message);
	auto const error = static_cast<jthrowable>(env->NewObject(cls, ctor, jint(kind), text));
	if (error) env->Throw(error);
	env->DeleteLocalRef(text);
	env->DeleteLocalRef(cls);
}

struct engine_lease {
	std::shared_ptr<engine_entry> entry;
	explicit engine_lease(std::shared_ptr<engine_entry> e = {}) : entry(std::move(e)) {
		if (entry) { std::lock_guard<std::mutex> lock(entry->calls_mutex); ++entry->calls; }
	}
	engine_lease(engine_lease const&) = delete;
	engine_lease(engine_lease&&) = default;
	~engine_lease() { if (entry) { std::lock_guard<std::mutex> lock(entry->calls_mutex); --entry->calls; entry->calls_changed.notify_all(); } }
	explicit operator bool() const { return bool(entry); }
	engine_entry* operator->() const { return entry.get(); }
};
engine_lease find(JNIEnv* env, jlong id)
{
	std::lock_guard<std::mutex> lock(registry_mutex);
	auto const i = registry.find(id);
	if (i != registry.end()) return engine_lease(i->second);
	throw_error(env, int(dtv::error_kind::invalid_handle), "The torrent engine is closed");
	return engine_lease();
}

// Inputs (paths, magnet links, addresses) are ASCII in practice; modified UTF-8 matches UTF-8 for them.
std::string string(JNIEnv* env, jstring s)
{
	if (!s) return {};
	char const* chars = env->GetStringUTFChars(s, nullptr);
	std::string ret(chars ? chars : "");
	if (chars) env->ReleaseStringUTFChars(s, chars);
	return ret;
}

std::vector<std::string> strings(JNIEnv* env, jobjectArray array)
{
	std::vector<std::string> ret;
	if (!array) return ret;
	jsize const n = env->GetArrayLength(array);
	for (jsize i = 0; i < n; ++i)
	{
		auto const item = static_cast<jstring>(env->GetObjectArrayElement(array, i));
		ret.push_back(string(env, item));
		env->DeleteLocalRef(item);
	}
	return ret;
}

std::string json_escape(std::string const& s)
{
	std::string out;
	for (char c : s)
	{
		switch (c)
		{
			case '"': out += "\\\""; break;
			case '\\': out += "\\\\"; break;
			case '\n': out += "\\n"; break;
			case '\r': out += "\\r"; break;
			case '\t': out += "\\t"; break;
			default:
				if (static_cast<unsigned char>(c) < 0x20)
				{
					char buf[8];
					std::snprintf(buf, sizeof(buf), "\\u%04x", c);
					out += buf;
				}
				else out += c;
		}
	}
	return out;
}

// Runs fn, turning engine errors and other exceptions into Java exceptions.
template <typename T, typename F>
T guarded(JNIEnv* env, T fallback, F&& fn)
{
	try
	{
		return fn();
	}
	catch (dtv::engine_error const& e)
	{
		throw_error(env, int(e.kind), e.what());
	}
	catch (std::exception const& e)
	{
		throw_error(env, int(dtv::error_kind::engine_failed), e.what());
	}
	return fallback;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_tv_stream_torrent_TorrentEngine_nativeCreate(JNIEnv* env, jclass, jstring cache_dir
	, jlong cache_bytes, jlong reserve_bytes, jstring listen, jboolean dht, jboolean lsd, jboolean upnp
	, jboolean natpmp, jboolean pex, jboolean multiple_per_ip, jboolean public_network_only, jstring ca_file, jint read_ahead_seconds)
{
	return guarded<jlong>(env, 0, [&] {
		auto entry = std::make_shared<engine_entry>();
		dtv::engine_config cfg;
		cfg.cache_dir = string(env, cache_dir);
		cfg.cache_bytes = cache_bytes;
		cfg.reserve_bytes = reserve_bytes;
		cfg.listen_interfaces = string(env, listen);
		cfg.dht = dht;
		cfg.lsd = lsd;
		cfg.upnp = upnp;
		cfg.natpmp = natpmp;
		cfg.pex = pex;
		cfg.multiple_connections_per_ip = multiple_per_ip;
		cfg.public_network_only = public_network_only;
		cfg.ca_file = string(env, ca_file);
		if (read_ahead_seconds > 0) cfg.read_ahead_seconds = read_ahead_seconds;
		std::weak_ptr<engine_entry> weak = entry;
		cfg.log = [weak](std::string const& line) {
			auto e = weak.lock();
			if (!e) return;
			std::lock_guard<std::mutex> lock(e->log_mutex);
			e->log.push_back(line);
			if (e->log.size() > 200) e->log.pop_front();
		};
		entry->engine = std::make_unique<dtv::engine>(std::move(cfg));
		std::lock_guard<std::mutex> lock(registry_mutex);
		jlong const id = next_engine_id++;
		registry[id] = std::move(entry);
		return id;
	});
}

JNIEXPORT void JNICALL Java_tv_stream_torrent_TorrentEngine_nativeDestroy(JNIEnv*, jclass, jlong id)
{
	std::shared_ptr<engine_entry> entry;
	{
		std::lock_guard<std::mutex> lock(registry_mutex);
		auto const i = registry.find(id);
		if (i == registry.end()) return;
		entry = std::move(i->second);
		registry.erase(i);
	}
	// No new leases can enter. Wake blocked operations before waiting for existing ones.
	entry->engine->request_stop();
	{ std::unique_lock<std::mutex> lock(entry->calls_mutex); entry->calls_changed.wait(lock, [&] { return entry->calls == 0; }); }
	entry->engine.reset();
}

JNIEXPORT jlong JNICALL Java_tv_stream_torrent_TorrentEngine_nativeAdd(JNIEnv* env, jclass, jlong id
	, jbyteArray metainfo, jstring magnet, jstring info_hash, jobjectArray trackers, jobjectArray peers, jobjectArray fallback_trackers)
{
	auto entry = find(env, id);
	if (!entry) return 0;
	return guarded<jlong>(env, 0, [&] {
		dtv::source src;
		if (metainfo)
		{
			jsize const n = env->GetArrayLength(metainfo);
			src.metainfo.resize(std::size_t(n));
			env->GetByteArrayRegion(metainfo, 0, n, reinterpret_cast<jbyte*>(src.metainfo.data()));
		}
		src.magnet = string(env, magnet);
		src.info_hash = string(env, info_hash);
		src.trackers = strings(env, trackers);
		src.fallback_trackers = strings(env, fallback_trackers);
		src.peers = strings(env, peers);
		return jlong(entry->engine->add(src));
	});
}

// Returns the files as JSON, null if the wait timed out, or throws.
JNIEXPORT jstring JNICALL Java_tv_stream_torrent_TorrentEngine_nativeAwaitMetadata(JNIEnv* env, jclass, jlong id
	, jlong torrent, jlong wait_ms)
{
	auto entry = find(env, id);
	if (!entry) return nullptr;
	return guarded<jstring>(env, nullptr, [&]() -> jstring {
		std::vector<dtv::file_info> files;
		try
		{
			files = entry->engine->wait_metadata(std::uint64_t(torrent), std::chrono::milliseconds(wait_ms));
		}
		catch (dtv::engine_error const& e)
		{
			if (e.kind == dtv::error_kind::timeout) return nullptr;
			throw;
		}
		std::string json = "[";
		for (std::size_t i = 0; i < files.size(); ++i)
		{
			if (i) json += ",";
			json += "{\"index\":" + std::to_string(files[i].index) + ",\"path\":\"" + json_escape(files[i].path)
				+ "\",\"size\":" + std::to_string(files[i].size) + "}";
		}
		json += "]";
		return utf8_string(env, json);
	});
}

JNIEXPORT void JNICALL Java_tv_stream_torrent_TorrentEngine_nativeSelectFile(JNIEnv* env, jclass, jlong id
	, jlong torrent, jint file)
{
	auto entry = find(env, id);
	if (!entry) return;
	guarded<int>(env, 0, [&] { entry->engine->select_file(std::uint64_t(torrent), file); return 0; });
}

JNIEXPORT void JNICALL Java_tv_stream_torrent_TorrentEngine_nativeSetBitrate(JNIEnv* env, jclass, jlong id
	, jlong torrent, jlong bytes_per_second)
{
	auto entry = find(env, id);
	if (!entry) return;
	guarded<int>(env, 0, [&] { entry->engine->set_bitrate(std::uint64_t(torrent), bytes_per_second); return 0; });
}

JNIEXPORT void JNICALL Java_tv_stream_torrent_TorrentEngine_nativeSetDemand(JNIEnv* env, jclass, jlong id, jlong torrent, jlong bytes, jint seconds)
{
	auto entry = find(env, id); if (!entry) return;
	guarded<int>(env, 0, [&] { entry->engine->set_demand(std::uint64_t(torrent), bytes, seconds); return 0; });
}
JNIEXPORT jbyteArray JNICALL Java_tv_stream_torrent_TorrentEngine_nativeMetainfo(JNIEnv* env, jclass, jlong id, jlong torrent)
{
	auto entry = find(env, id); if (!entry) return nullptr;
	return guarded<jbyteArray>(env, nullptr, [&] {
		auto bytes = entry->engine->metainfo(std::uint64_t(torrent));
		jbyteArray result = env->NewByteArray(jsize(bytes.size()));
		if (result) env->SetByteArrayRegion(result, 0, jsize(bytes.size()), reinterpret_cast<jbyte const*>(bytes.data()));
		return result;
	});
}

JNIEXPORT void JNICALL Java_tv_stream_torrent_TorrentEngine_nativeAddPeers(JNIEnv* env, jclass, jlong id
	, jlong torrent, jobjectArray peers)
{
	auto entry = find(env, id);
	if (!entry) return;
	guarded<int>(env, 0, [&] { entry->engine->add_peers(std::uint64_t(torrent), strings(env, peers)); return 0; });
}

JNIEXPORT jstring JNICALL Java_tv_stream_torrent_TorrentEngine_nativeStats(JNIEnv* env, jclass, jlong id
	, jlong torrent)
{
	auto entry = find(env, id);
	if (!entry) return nullptr;
	return guarded<jstring>(env, nullptr, [&] {
		dtv::torrent_stats const s = entry->engine->stats(std::uint64_t(torrent));
		std::string json = "{\"state\":\"" + json_escape(s.state) + "\",\"error\":\"" + json_escape(s.error)
			+ "\",\"errorKind\":" + std::to_string(s.error_kind)
			+ ",\"peers\":" + std::to_string(s.peers) + ",\"seeds\":" + std::to_string(s.seeds)
			+ ",\"downloadRate\":" + std::to_string(s.download_rate) + ",\"uploadRate\":" + std::to_string(s.upload_rate)
			+ ",\"totalDownload\":" + std::to_string(s.total_download)
			+ ",\"totalUpload\":" + std::to_string(s.total_upload)
			+ ",\"verifiedReadBytes\":" + std::to_string(s.verified_read_bytes)
			+ ",\"urgentReservationFailures\":" + std::to_string(s.urgent_reservation_failures)
			+ ",\"obsoletePartials\":" + std::to_string(s.obsolete_partials)
			+ ",\"pieceLength\":" + std::to_string(s.piece_length) + ",\"pieces\":" + std::to_string(s.num_pieces)
			+ ",\"hashFailures\":" + std::to_string(s.hash_failures)
			+ ",\"evictionsRequested\":" + std::to_string(s.evictions_requested)
			+ ",\"cache\":{\"slots\":" + std::to_string(s.cache.slots) + ",\"used\":" + std::to_string(s.cache.used)
			+ ",\"verified\":" + std::to_string(s.cache.verified) + ",\"pinned\":" + std::to_string(s.cache.pinned)
			+ ",\"evicted\":" + std::to_string(s.cache.evicted) + ",\"fileBytes\":" + std::to_string(s.cache.file_bytes)
			+ ",\"allocatedBytes\":" + std::to_string(s.cache.allocated_bytes) + "}"
			+ ",\"disk\":{\"bufferBytes\":" + std::to_string(s.disk.buffer_bytes)
			+ ",\"queuedWriteBytes\":" + std::to_string(s.disk.queued_write_bytes)
			+ ",\"blockedWrites\":" + std::to_string(s.disk.blocked_writes) + "}}";
		return utf8_string(env, json);
	});
}

JNIEXPORT void JNICALL Java_tv_stream_torrent_TorrentEngine_nativeRemove(JNIEnv* env, jclass, jlong id
	, jlong torrent)
{
	auto entry = find(env, id);
	if (!entry) return;
	guarded<int>(env, 0, [&] { entry->engine->remove(std::uint64_t(torrent)); return 0; });
}

JNIEXPORT jlong JNICALL Java_tv_stream_torrent_TorrentEngine_nativeOpenReader(JNIEnv* env, jclass, jlong id
	, jlong torrent, jint file)
{
	auto entry = find(env, id);
	if (!entry) return 0;
	return guarded<jlong>(env, 0, [&] { return jlong(entry->engine->open_reader(std::uint64_t(torrent), file)); });
}

JNIEXPORT jlong JNICALL Java_tv_stream_torrent_TorrentEngine_nativeFileSize(JNIEnv* env, jclass, jlong id
	, jlong reader)
{
	auto entry = find(env, id);
	if (!entry) return 0;
	return guarded<jlong>(env, 0, [&] { return jlong(entry->engine->file_size(std::uint64_t(reader))); });
}

// Copies verified bytes into buffer[offset, offset + length). Returns the count, 0 at the end of the file, or -1
// when nothing arrived within wait_ms; the Java side then checks for interruption and calls again.
JNIEXPORT jint JNICALL Java_tv_stream_torrent_TorrentEngine_nativeRead(JNIEnv* env, jclass, jlong id
	, jlong reader, jlong position, jbyteArray buffer, jint offset, jint length, jlong wait_ms)
{
	auto entry = find(env, id);
	if (!entry) return 0;
	if (offset < 0 || length < 0 || offset > env->GetArrayLength(buffer) - length)
	{
		throw_error(env, int(dtv::error_kind::invalid_file), "Read outside the buffer");
		return 0;
	}
	// One reused buffer per thread: the data is copied once more, into the Java array.
	thread_local std::vector<char> scratch;
	std::size_t const chunk = std::min<std::size_t>(std::size_t(length), std::size_t(1) << 20);
	if (scratch.size() < chunk) scratch.resize(chunk);
	return guarded<jint>(env, 0, [&] {
		int const n = entry->engine->read(std::uint64_t(reader), position, scratch.data(), int(chunk)
			, std::chrono::milliseconds(wait_ms));
		if (n > 0) env->SetByteArrayRegion(buffer, offset, n, reinterpret_cast<jbyte const*>(scratch.data()));
		return jint(n);
	});
}

JNIEXPORT void JNICALL Java_tv_stream_torrent_TorrentEngine_nativeCancel(JNIEnv* env, jclass, jlong id, jlong reader)
{
	auto entry = find(env, id);
	if (!entry) return;
	guarded<int>(env, 0, [&] { entry->engine->cancel(std::uint64_t(reader)); return 0; });
}

JNIEXPORT void JNICALL Java_tv_stream_torrent_TorrentEngine_nativeCloseReader(JNIEnv* env, jclass, jlong id
	, jlong reader)
{
	auto entry = find(env, id);
	if (!entry) return;
	guarded<int>(env, 0, [&] { entry->engine->close_reader(std::uint64_t(reader)); return 0; });
}

JNIEXPORT jobjectArray JNICALL Java_tv_stream_torrent_TorrentEngine_nativeDiagnostics(JNIEnv* env, jclass, jlong id)
{
	auto entry = find(env, id);
	if (!entry) return nullptr;
	std::vector<std::string> lines;
	{
		std::lock_guard<std::mutex> lock(entry->log_mutex);
		lines.assign(entry->log.begin(), entry->log.end());
	}
	jclass const cls = env->FindClass("java/lang/String");
	jobjectArray const ret = env->NewObjectArray(jsize(lines.size()), cls, nullptr);
	for (std::size_t i = 0; i < lines.size(); ++i)
	{
		jstring const s = utf8_string(env, lines[i]);
		env->SetObjectArrayElement(ret, jsize(i), s);
		env->DeleteLocalRef(s);
	}
	return ret;
}

JNIEXPORT jstring JNICALL Java_tv_stream_torrent_TorrentEngine_nativeVersion(JNIEnv* env, jclass)
{
	return utf8_string(env, dtv::engine::version());
}

} // extern "C"
