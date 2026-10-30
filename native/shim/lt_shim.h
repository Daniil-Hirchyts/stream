/* The C interface the Rust engine core uses to drive libtorrent. The shim only speaks libtorrent: every decision
 * (which pieces, deadlines, what to evict, when a torrent fails) is the engine's. Pieces are stored in the Rust
 * piece cache through cache_disk_io. Every function catches libtorrent's exceptions; none blocks on the engine.
 *
 * Strings passed in are borrowed for the call. Messages written to `error` are truncated to `error_size`. */
#ifndef STREAM_LT_SHIM_H
#define STREAM_LT_SHIM_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct lts_session lts_session;
typedef struct lts_params lts_params;
typedef struct lts_torrent lts_torrent;
typedef struct lts_info lts_info;

typedef struct lts_config
{
	const char* cache_dir;
	int64_t cache_bytes;
	int connections_limit;
	const char* listen_interfaces;
	bool dht, lsd, upnp, natpmp;
	bool public_network_only;
	bool multiple_connections_per_ip;
	/* Peer disconnections and errors become log events. */
	bool log_peers;
	/* PEM trust roots for HTTPS trackers and web seeds; empty keeps OpenSSL's defaults. */
	const char* ca_file;
	const char* user_agent;
	/* Called on libtorrent's network thread when events are waiting; it must only signal. */
	void (*notify)(void* context);
	void* notify_context;
} lts_config;

lts_session* lts_session_create(lts_config const* config, char* error, size_t error_size);
/* Aborts the session and waits for its threads. Every lts_torrent of it must be freed first. */
void lts_session_destroy(lts_session* session);
const char* lts_version(void);

/* Exactly one of metainfo (with its length), magnet or info_hash (40 or 64 hex digits). Bounded parsing. */
lts_params* lts_params_parse(uint8_t const* metainfo, size_t metainfo_size, const char* magnet, const char* info_hash,
	char* error, size_t error_size);
/* The torrent's piece length when the source carries metadata, else 0. */
int lts_params_piece_length(lts_params const* params);
void lts_params_add_tracker(lts_params* params, const char* url);
size_t lts_params_tracker_count(lts_params const* params);
/* A numeric IPv4 or IPv6 address. */
void lts_params_add_peer(lts_params* params, const char* address, uint16_t port);
void lts_params_free(lts_params* params);

/* Consumes params (also on failure). Nothing downloads until pieces are prioritized. `tag` comes back in events. */
lts_torrent* lts_add(lts_session* session, lts_params* params, uint64_t tag, bool dht, bool lsd, bool pex,
	char* error, size_t error_size);
void lts_torrent_free(lts_torrent* torrent);
/* Starts removing the torrent; LTS_EVENT_REMOVED follows. */
void lts_remove(lts_torrent* torrent);
void lts_pause(lts_torrent* torrent);

/* The torrent's layout, or null before its metadata arrived. */
lts_info* lts_torrent_info(lts_torrent* torrent);
int lts_info_piece_length(lts_info const* info);
int lts_info_num_pieces(lts_info const* info);
int lts_info_piece_size(lts_info const* info, int piece);
int lts_info_num_files(lts_info const* info);
/* The file's path (valid while info lives), size, offset in piece space and whether it is a pad file. */
const char* lts_info_file(lts_info const* info, int file, int64_t* size, int64_t* offset, bool* pad);
void lts_info_free(lts_info* info);

/* The torrent's piece cache, retained for the caller (stream_cache_release), or null before its storage exists. */
void const* lts_torrent_cache(lts_torrent* torrent);

void lts_prioritize_pieces(lts_torrent* torrent, uint8_t const* priorities, int count);
void lts_clear_piece_deadlines(lts_torrent* torrent);
void lts_set_piece_deadline(lts_torrent* torrent, int piece, int milliseconds);
bool lts_have_piece(lts_torrent* torrent, int piece);
void lts_connect_peer(lts_torrent* torrent, const char* address, uint16_t port);

/* Evicts pieces (torrent_handle::evict_pieces). On libtorrent's network thread, once the torrent forgot them, their
 * cache slots are released behind every disk job queued before, then `done` receives the pieces actually evicted.
 * `drop` frees the context whether or not `done` ran (the session may end first). */
void lts_evict_pieces(lts_torrent* torrent, int const* pieces, int count,
	void (*done)(void* context, int const* evicted, int count), void (*drop)(void* context), void* context);

typedef struct lts_status
{
	int peers, seeds;
	int download_rate, upload_rate;
	int64_t total_download, total_upload;
} lts_status;
bool lts_torrent_status(lts_torrent* torrent, lts_status* out);

/* The torrent file with its trackers, through `sink`. False with a message when metadata is missing or too large. */
bool lts_metainfo(lts_torrent* torrent, void (*sink)(void* context, uint8_t const* data, size_t size), void* context,
	char* error, size_t error_size);

typedef struct lts_disk_stats
{
	int64_t buffer_bytes;
	int64_t queued_write_bytes;
	int blocked_writes;
} lts_disk_stats;
void lts_session_disk_stats(lts_session* session, lts_disk_stats* out);

enum
{
	LTS_EVENT_LOG = 0,
	LTS_EVENT_METADATA_RECEIVED = 1,
	LTS_EVENT_PIECE_FINISHED = 2,
	LTS_EVENT_HASH_FAILED = 3,
	LTS_EVENT_METADATA_FAILED = 4,
	LTS_EVENT_TORRENT_ERROR = 5,
	LTS_EVENT_FILE_ERROR = 6,
	LTS_EVENT_REMOVED = 7,
};

typedef struct lts_event
{
	int kind;
	uint64_t tag;
	int piece;
	/* Log line or error message, valid during the callback. */
	const char* message;
} lts_event;
/* Delivers pending events in order. */
void lts_poll(lts_session* session, void (*sink)(void* context, lts_event const* event), void* context);

#ifdef __cplusplus
}
#endif

#endif
