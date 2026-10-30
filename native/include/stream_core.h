/* The Rust engine core's C ABI (native/core). Two parts:
 *
 * - The handle-based engine API: create, add, select, read, seek, cancel, close and stats. Engines, torrents and
 *   readers are 64-bit handles that are never reused, so a stale handle is refused rather than reaching a newer
 *   object. Functions return 0 or an error kind (STREAM_ERROR_*); stream_last_error() gives the calling thread's
 *   message.
 * - The piece cache, for libtorrent's disk subsystem (cache_disk_io). A stream_cache pointer is one reference:
 *   stream_cache_new returns one, stream_cache_retain adds one, stream_cache_release drops one. */
#ifndef STREAM_CORE_H
#define STREAM_CORE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Shared with Kotlin's TorrentException. */
enum
{
	STREAM_ERROR_INVALID_HANDLE = 1,
	STREAM_ERROR_CANCELLED = 2,
	STREAM_ERROR_CLOSED = 3,
	STREAM_ERROR_TIMEOUT = 4,
	STREAM_ERROR_BAD_SOURCE = 5,
	STREAM_ERROR_METADATA_FAILED = 6,
	STREAM_ERROR_PIECE_TOO_LARGE = 7,
	STREAM_ERROR_CACHE_TOO_SMALL = 8,
	STREAM_ERROR_LOW_STORAGE = 9,
	STREAM_ERROR_INVALID_FILE = 10,
	STREAM_ERROR_TORRENT_FAILED = 11,
	STREAM_ERROR_ENGINE_FAILED = 12,
};

/* The calling thread's last error message (truncated, NUL-terminated); returns its kind. */
int stream_last_error(char* out, size_t size);
/* The engine's version; returns its full length. */
size_t stream_version(char* out, size_t size);

typedef struct stream_config
{
	const char* cache_dir;
	int64_t cache_bytes;
	int64_t reserve_bytes;
	int max_piece_bytes;
	int min_slots;
	int connections_limit;
	const char* listen_interfaces;
	bool dht, lsd, upnp, natpmp, pex;
	bool public_network_only;
	bool multiple_connections_per_ip;
	const char* ca_file;
	const char* user_agent;
	int read_ahead_seconds;
	int min_read_ahead_pieces;
	int64_t default_bitrate;
	/* Diagnostics, on the engine thread. */
	void (*log)(void* context, const char* line);
	void* log_context;
} stream_config;

/* The defaults; null strings keep them. */
void stream_config_default(stream_config* out);
int stream_engine_create(stream_config const* config, uint64_t* engine);
/* Wakes blocked calls and refuses new work. */
void stream_engine_request_stop(uint64_t engine);
/* Refuses new calls, waits for running ones, then shuts the engine down. */
void stream_engine_destroy(uint64_t engine);

typedef struct stream_source
{
	uint8_t const* metainfo;
	size_t metainfo_size;
	const char* magnet;
	const char* info_hash;
	const char* const* trackers;
	size_t num_trackers;
	const char* const* fallback_trackers;
	size_t num_fallback_trackers;
	const char* const* peers;
	size_t num_peers;
} stream_source;

int stream_add(uint64_t engine, stream_source const* source, uint64_t* torrent);

typedef struct stream_file
{
	int index;
	int64_t size;
	char* path;
} stream_file;

/* `cancelled` (a std::atomic<bool>, or null) ends the wait early. Free the list with stream_files_free. */
int stream_wait_metadata(uint64_t engine, uint64_t torrent, int64_t timeout_ms, void const* cancelled,
	stream_file** files, size_t* count);
void stream_files_free(stream_file* files, size_t count);
int stream_select_file(uint64_t engine, uint64_t torrent, int file);
int stream_set_bitrate(uint64_t engine, uint64_t torrent, int64_t bytes_per_second);
int stream_set_demand(uint64_t engine, uint64_t torrent, int64_t bytes_per_second, int ahead_seconds);
int stream_metainfo(uint64_t engine, uint64_t torrent, uint8_t** data, size_t* size);
void stream_bytes_free(uint8_t* data, size_t size);
int stream_add_peers(uint64_t engine, uint64_t torrent, const char* const* peers, size_t count);
int stream_remove(uint64_t engine, uint64_t torrent);

typedef struct stream_cache_stats
{
	int slots, used, verified, pinned;
	int64_t evicted;
	int64_t file_bytes;
	int64_t allocated_bytes;
} stream_cache_stats;

typedef struct stream_disk_stats
{
	int64_t buffer_bytes;
	int64_t queued_write_bytes;
	int blocked_writes;
} stream_disk_stats;

enum { STREAM_STATE_METADATA = 0, STREAM_STATE_READY = 1, STREAM_STATE_STREAMING = 2, STREAM_STATE_FAILED = 3 };

typedef struct stream_stats
{
	int state;
	char* error;
	int error_kind;
	int peers, seeds;
	int download_rate, upload_rate;
	int64_t total_download, total_upload;
	int piece_length, num_pieces;
	int hash_failures;
	int evictions_requested;
	int urgent_reservation_failures, obsolete_partials;
	int64_t verified_read_bytes;
	stream_cache_stats cache;
	stream_disk_stats disk;
} stream_stats;

/* Free with stream_stats_free. */
int stream_get_stats(uint64_t engine, uint64_t torrent, stream_stats* out);
void stream_stats_free(stream_stats* stats);

int stream_open_reader(uint64_t engine, uint64_t torrent, int file, uint64_t* reader);
int stream_file_size(uint64_t engine, uint64_t reader, int64_t* size);
/* *read: bytes copied (at most to the end of a piece), 0 at the end of the file, -1 when nothing arrived in time. */
int stream_read(uint64_t engine, uint64_t reader, int64_t position, uint8_t* buffer, size_t size, int64_t wait_ms,
	int* read);
/* Moves the reader before its next read, so the window starts downloading there at once. */
int stream_seek(uint64_t engine, uint64_t reader, int64_t position);
/* Wakes a blocked read, which then fails with CANCELLED; later reads fail too. */
int stream_cancel(uint64_t engine, uint64_t reader);
int stream_close_reader(uint64_t engine, uint64_t reader);

/* ---- Piece cache ---- */

typedef struct stream_cache stream_cache;

/* 0 ok, 1 aborted, 2 invalid argument, 3 end of file, 4 OS error (os_error). */
typedef struct stream_io
{
	int kind;
	int os_error;
} stream_io;

enum { STREAM_WAIT_READY = 0, STREAM_WAIT_TIMEOUT = 1, STREAM_WAIT_CANCELLED = 2, STREAM_WAIT_CLOSED = 3 };
enum { STREAM_PIECE_RESERVED = 0, STREAM_PIECE_PARTIAL = 1, STREAM_PIECE_COMPLETE = 2, STREAM_PIECE_VERIFIED = 3 };

/* Slots a cache budget holds, after filesystem allocation overhead. */
int stream_cache_slots(int64_t bytes, int piece_length);
/* Pad ranges as parallel arrays (piece, offset, length). Null with errno and a message on failure. */
stream_cache const* stream_cache_new(const char* path, int piece_length, int const* piece_sizes, int num_pieces,
	int const* pad_pieces, int const* pad_offsets, int const* pad_lengths, int num_pads, int slots, bool preallocate,
	int* error_number, char* message, size_t message_size);
void stream_cache_retain(stream_cache const* cache);
void stream_cache_release(stream_cache const* cache);

int stream_cache_piece_length(stream_cache const* cache);
int stream_cache_num_pieces(stream_cache const* cache);
int stream_cache_piece_size(stream_cache const* cache, int piece);
int stream_cache_slot_count(stream_cache const* cache);
bool stream_cache_reserve(stream_cache const* cache, int piece);
void stream_cache_unreserve(stream_cache const* cache, int piece);
bool stream_cache_has_slot(stream_cache const* cache, int piece);
bool stream_cache_has_data(stream_cache const* cache, int piece);
bool stream_cache_complete(stream_cache const* cache, int piece);
bool stream_cache_block_written(stream_cache const* cache, int piece, int offset);
int stream_cache_free_slots(stream_cache const* cache);
/* False without error when the block must wait for a slot. */
bool stream_cache_write(stream_cache const* cache, int piece, int offset, uint8_t const* data, size_t size, stream_io* io);
void stream_cache_read(stream_cache const* cache, int piece, int offset, uint8_t* out, size_t size, stream_io* io);
void stream_cache_clear(stream_cache const* cache, int piece);
void stream_cache_release_piece(stream_cache const* cache, int piece);
void stream_cache_set_verified(stream_cache const* cache, int piece);
bool stream_cache_verified(stream_cache const* cache, int piece);
int stream_cache_wait_verified(stream_cache const* cache, int piece, int64_t timeout_ns, void const* cancelled);
/* False when the piece is not verified (anymore). */
bool stream_cache_read_verified(stream_cache const* cache, int piece, int offset, uint8_t* out, size_t size, stream_io* io);
void stream_cache_wake_all(stream_cache const* cache);
void stream_cache_close(stream_cache const* cache);
int stream_cache_eviction_candidates(stream_cache const* cache, int count, bool (*is_protected)(void* context, int piece),
	void* context, int* out, int capacity);
void stream_cache_begin_release(stream_cache const* cache, int const* pieces, int count);
void stream_cache_cancel_release(stream_cache const* cache, int piece);
int stream_cache_pieces(stream_cache const* cache, int* pieces, int* kinds, int capacity);
/* Called on the freeing thread, outside the cache's lock, whenever a slot becomes free. Null clears it. `drop`
 * receives the context once nothing can call `callback` with it any more, including a call in progress. */
void stream_cache_on_slot_freed(stream_cache const* cache, void (*callback)(void* context), void (*drop)(void* context),
	void* context);
void stream_cache_get_stats(stream_cache const* cache, stream_cache_stats* out);

#ifdef __cplusplus
}
#endif

#endif
