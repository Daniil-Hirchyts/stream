package tv.daniil.cast;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Connects the network server to the player screen. Commands run on the main thread; a cast that arrives while
 * the app is closed opens the player and waits for it.
 */
final class CastHub {
    interface Screen {
        JSONObject command(JSONObject command) throws Exception;
        boolean isVisible();
    }

    /** Commands that bring the player to the front. */
    private static final Set<String> FOREGROUND = new HashSet<>(Arrays.asList("load", "prepare", "fail", "play", "episode"));
    private static final long TIMEOUT_MS = 5000;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final List<JSONObject> pending = new ArrayList<>();
    @Nullable private static Screen screen;
    @Nullable private static volatile JSONObject status;

    private CastHub() {}

    /** Called by the player once it can take commands; it receives any that arrived while it opened. */
    static void attach(Screen player) {
        screen = player;
        List<JSONObject> queued = new ArrayList<>(pending);
        pending.clear();
        for (JSONObject command : queued) {
            try {
                player.command(command);
            } catch (Exception ignored) { /* The player shows its own errors. */ }
        }
    }

    static void detach(Screen player) {
        if (screen != player) return;
        screen = null;
        status = null;
    }

    static void publish(Screen owner, JSONObject snapshot) {
        // A finishing activity can still receive player callbacks after its
        // replacement attaches. Only the current screen owns the status.
        if (screen == owner) status = snapshot;
    }

    /** The player's last status, or "closed" while the app isn't open. */
    static JSONObject status() throws Exception {
        JSONObject snapshot = status;
        if (snapshot != null) return snapshot;
        return new JSONObject().put("app", "Daniil Cast").put("phase", "closed")
                .put("updatedAt", System.currentTimeMillis());
    }

    /** Runs a command from the network on the main thread. */
    static JSONObject command(Context context, JSONObject command) throws Exception {
        AtomicReference<Object> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        main.post(() -> {
            try {
                result.set(run(context, command));
            } catch (Exception exception) {
                result.set(exception);
            }
            done.countDown();
        });
        if (!done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new CastServer.Failure(503, "busy", "The TV didn't answer in time");
        }
        if (result.get() instanceof Exception) throw (Exception) result.get();
        return (JSONObject) result.get();
    }

    /** Opens the player to show the pairing code. */
    static void showPairing(Context context) {
        main.post(() -> {
            if (screen == null || !screen.isVisible()) open(context);
        });
    }

    private static JSONObject run(Context context, JSONObject command) throws Exception {
        String action = command.optString("action");
        boolean foreground = FOREGROUND.contains(action);
        if (screen == null) {
            if ("stop".equals(action)) return new JSONObject().put("ok", true);
            if (!"load".equals(action) && !"prepare".equals(action) && !"fail".equals(action)) {
                throw new CastServer.Failure(409, "not-playing", "Nothing is playing on the TV");
            }
            pending.add(command);
            open(context);
            return new JSONObject().put("ok", true).put("queued", true);
        }
        if (foreground && !screen.isVisible()) open(context);
        return screen.command(command);
    }

    private static void open(Context context) {
        context.startActivity(new Intent(context, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
