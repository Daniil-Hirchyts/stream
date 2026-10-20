package tv.daniil.cast;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.util.AtomicFile;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Receives a media URL through the owner's authenticated ADB connection. */
public final class PlayerActivity extends Activity {
    private ExoPlayer player;
    private PlayerView playerView;
    private TextView message;
    private MediaSession session;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String title = "", error = "", errorMessage = "";
    private int httpStatus = 0;
    private final Runnable tick = new Runnable() {
        @Override public void run() { publishStatus(); handler.postDelayed(this, 1000); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        playerView = new PlayerView(this);
        playerView.setControllerAutoShow(true);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        message = new TextView(this);
        message.setTextColor(Color.WHITE);
        message.setTextSize(25);
        message.setGravity(Gravity.CENTER);
        message.setPadding(80, 40, 80, 40);
        root.addView(message, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        playerView.setPlayer(player);
        playerView.requestFocus();
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) message.setVisibility(View.GONE);
                publishStatus();
            }
            @Override public void onPlayerError(PlaybackException exception) {
                error = exception.getErrorCodeName();
                Throwable cause = exception.getCause();
                httpStatus = 0;
                for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause()) {
                    if (cause instanceof DefaultHttpDataSource.InvalidResponseCodeException) {
                        httpStatus = ((DefaultHttpDataSource.InvalidResponseCodeException) cause).responseCode;
                        error += " (HTTP " + httpStatus + ")";
                        break;
                    }
                }
                if (httpStatus == 410 || httpStatus == 404) {
                    errorMessage = "The video server rejected this stream URL (HTTP " + httpStatus
                            + "). Reload the video in Chrome, then Find videos and Cast again.";
                } else if (httpStatus == 401 || httpStatus == 403) {
                    errorMessage = "The video server denied access (HTTP " + httpStatus
                            + "). This stream may require a browser session that cannot be sent to the TV.";
                } else if (httpStatus != 0) {
                    errorMessage = "The video server returned HTTP " + httpStatus + ". Try again or choose another source.";
                } else if (exception.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                        || exception.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                        || exception.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                        || exception.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED) {
                    errorMessage = "The TV could not decode this audio or video format. Choose a different quality or stream.";
                } else {
                    errorMessage = "Playback failed. Try casting a fresh stream from Chrome.";
                }
                showMessage("Couldn’t play this stream\n\n" + errorMessage + "\n\n" + error);
                publishStatus();
            }
        });
        session = new MediaSession(this, "Daniil Cast");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { player.play(); }
            @Override public void onPause() { player.pause(); }
            @Override public void onStop() { player.stop(); finish(); }
            @Override public void onSeekTo(long pos) { player.seekTo(Math.max(0, pos)); }
            @Override public void onFastForward() { player.seekTo(player.getCurrentPosition() + 10000); }
            @Override public void onRewind() { player.seekTo(Math.max(0, player.getCurrentPosition() - 10000)); }
        });
        session.setActive(true);
        handle(getIntent());
        handler.post(tick);
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        String command = intent.getStringExtra("command");
        if ("pause".equals(command)) player.pause();
        else if ("play".equals(command)) player.play();
        else if ("seek".equals(command)) player.seekTo(Math.max(0, intent.getLongExtra("position", 0)));
        else if ("stop".equals(command)) { player.stop(); finishAndRemoveTask(); }
        else if ("load".equals(command)) load(intent);
        else if (player.getMediaItemCount() == 0) {
            showMessage("Daniil Cast\n\nReady for a video from Chrome\n\nOpen the Daniil Cast extension on your Mac, choose a video and press Cast.");
        }
        publishStatus();
    }

    private void load(Intent intent) {
        try {
            String url = intent.getStringExtra("url");
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
                throw new IllegalArgumentException("A direct HTTP(S) media URL is required");
            }
            title = intent.getStringExtra("title");
            if (title == null) title = "Video from Chrome";
            error = "";
            errorMessage = "";
            httpStatus = 0;
            String userAgent = "DaniilCast/0.1.1";
            Map<String, String> headers = new HashMap<>();
            JSONObject supplied = new JSONObject(intent.getStringExtra("headers") == null ? "{}" : intent.getStringExtra("headers"));
            for (Iterator<String> names = supplied.keys(); names.hasNext();) {
                String name = names.next();
                String value = supplied.getString(name);
                if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) continue;
                if (name.equalsIgnoreCase("User-Agent")) userAgent = value;
                else if (name.equalsIgnoreCase("Referer")) headers.put("Referer", value);
                else if (name.equalsIgnoreCase("Origin")) headers.put("Origin", value);
            }
            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    // Media3 applies this after default headers, so preserve Chrome's value here.
                    .setUserAgent(userAgent)
                    .setConnectTimeoutMs(15000).setReadTimeoutMs(30000)
                    .setDefaultRequestProperties(headers);
            MediaItem.Builder media = new MediaItem.Builder().setUri(url)
                    .setMediaMetadata(new MediaMetadata.Builder().setTitle(title).build());
            String mime = intent.getStringExtra("mime");
            if (mime != null && !mime.isEmpty()) media.setMimeType(mime);
            player.setMediaSource(new DefaultMediaSourceFactory(http).createMediaSource(media.build()));
            player.seekTo(Math.max(0, intent.getLongExtra("position", 0)));
            showMessage("Opening “" + title + "”…");
            player.prepare();
            player.play();
            playerView.hideController();
        } catch (Exception exception) {
            error = exception.getMessage();
            errorMessage = "Couldn’t open the video. Check the selected source in Chrome.";
            showMessage("Couldn’t open video\n\n" + error);
        }
    }

    private void showMessage(String text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
    }

    private void publishStatus() {
        if (player == null) return;
        try {
            int state = player.getPlaybackState();
            long duration = player.getDuration();
            JSONObject status = new JSONObject()
                    .put("app", "Daniil Cast").put("title", title).put("error", error)
                    .put("errorMessage", errorMessage).put("httpStatus", httpStatus)
                    .put("playing", player.isPlaying()).put("playWhenReady", player.getPlayWhenReady())
                    .put("state", state == Player.STATE_BUFFERING ? "buffering" : state == Player.STATE_READY ? "ready"
                            : state == Player.STATE_ENDED ? "ended" : "idle")
                    .put("position", player.getCurrentPosition()).put("duration", duration == C.TIME_UNSET ? 0 : duration)
                    .put("updatedAt", System.currentTimeMillis());
            AtomicFile file = new AtomicFile(new File(getFilesDir(), "status.json"));
            FileOutputStream stream = file.startWrite();
            try {
                stream.write(status.toString().getBytes(StandardCharsets.UTF_8));
                file.finishWrite(stream);
            } catch (Exception exception) { file.failWrite(stream); }
            session.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP
                            | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND)
                    .setState(player.isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            player.getCurrentPosition(), player.isPlaying() ? 1 : 0).build());
        } catch (Exception ignored) { /* A status read must never interrupt playback. */ }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && player != null) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (player.getPlayWhenReady()) player.pause(); else player.play(); return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY: player.play(); return true;
                case KeyEvent.KEYCODE_MEDIA_PAUSE: player.pause(); return true;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: player.seekTo(player.getCurrentPosition() + 10000); return true;
                case KeyEvent.KEYCODE_MEDIA_REWIND: player.seekTo(Math.max(0, player.getCurrentPosition() - 10000)); return true;
                default: break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onStop() {
        super.onStop();
        if (player != null) { player.pause(); publishStatus(); }
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(tick);
        if (player != null) { player.stop(); publishStatus(); playerView.setPlayer(null); player.release(); player = null; }
        if (session != null) session.release();
        super.onDestroy();
    }
}
