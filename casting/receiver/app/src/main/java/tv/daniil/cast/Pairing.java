package tv.daniil.cast;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Who may control this TV. A Mac pairs by typing the six-digit code the TV shows; it then gets a long random token.
 * Only a hash of each token is stored. A shown code is valid for ten minutes and five guesses.
 */
final class Pairing {
    interface Listener {
        /** Called on the main thread when a code appears or goes, or a device pairs or is removed. */
        void onPairingChanged(@Nullable String pairedClient);
    }

    private static final long CODE_LIFETIME_MS = 10 * 60_000;
    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCKOUT_MS = 60_000;
    private static final long WRONG_GUESS_DELAY_MS = 500;
    private static final int MAX_CLIENTS = 8;
    private static Pairing instance;

    private final SharedPreferences prefs;
    private final SecureRandom random = new SecureRandom();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    @Nullable private String code;
    private long codeExpires;
    private int attempts;
    private long lockedUntil;

    static synchronized Pairing get(Context context) {
        if (instance == null) instance = new Pairing(context.getApplicationContext());
        return instance;
    }

    private Pairing(Context context) {
        prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE);
        if (!prefs.contains("id")) prefs.edit().putString("id", UUID.randomUUID().toString()).apply();
    }

    /** A stable, random identity, so a Mac can find this TV again after its address changes. */
    String id() {
        return prefs.getString("id", "");
    }

    void addListener(Listener listener) {
        listeners.add(listener);
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    int clientCount() {
        return clients().length();
    }

    /** The code on screen, or null when none is being shown. */
    @Nullable synchronized String activeCode() {
        if (code != null && SystemClock.elapsedRealtime() > codeExpires) code = null;
        return code;
    }

    /** Shows a code, keeping the current one if it is still valid; null for a minute after too many guesses. */
    @Nullable synchronized String showCode() {
        String current = activeCode();
        if (current != null) return current;
        if (SystemClock.elapsedRealtime() < lockedUntil) return null;
        code = String.format(Locale.US, "%06d", random.nextInt(1_000_000));
        codeExpires = SystemClock.elapsedRealtime() + CODE_LIFETIME_MS;
        attempts = 0;
        notifyChanged(null);
        return code;
    }

    synchronized void hideCode() {
        if (code == null) return;
        code = null;
        notifyChanged(null);
    }

    /** Returns a new token when {@code attempt} matches the code on screen, else null. */
    @Nullable synchronized String pair(String attempt, String client) {
        String current = activeCode();
        if (current == null) return null;
        if (!MessageDigest.isEqual(current.getBytes(StandardCharsets.US_ASCII),
                attempt.trim().replace(" ", "").getBytes(StandardCharsets.US_ASCII))) {
            // Guesses are slow, one at a time, and too many retire the code and pause pairing for a minute.
            SystemClock.sleep(WRONG_GUESS_DELAY_MS);
            if (++attempts >= MAX_ATTEMPTS) {
                lockedUntil = SystemClock.elapsedRealtime() + LOCKOUT_MS;
                hideCode();
            }
            return null;
        }
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = Base64.encodeToString(secret, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        try {
            JSONArray list = clients();
            JSONArray kept = new JSONArray();
            // The oldest pairing makes room once the list is full.
            for (int i = Math.max(0, list.length() - MAX_CLIENTS + 1); i < list.length(); i++) kept.put(list.get(i));
            String name = client.trim().isEmpty() ? "Chrome" : client.trim();
            kept.put(new JSONObject().put("hash", hash(token)).put("name", name)
                    .put("pairedAt", System.currentTimeMillis()));
            prefs.edit().putString("clients", kept.toString()).apply();
            code = null;
            notifyChanged(name);
        } catch (Exception exception) {
            return null;
        }
        return token;
    }

    // Unsynchronized: a slowed wrong guess must not hold up paired Macs. SharedPreferences reads are thread-safe.
    boolean authorized(@Nullable String token) {
        if (token == null || token.isEmpty()) return false;
        String hash = hash(token);
        JSONArray list = clients();
        boolean found = false;
        // Compare every entry, so the time taken says nothing about which one matched.
        for (int i = 0; i < list.length(); i++) {
            found |= MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),
                    list.optJSONObject(i).optString("hash").getBytes(StandardCharsets.US_ASCII));
        }
        return found;
    }

    synchronized void revoke(String token) {
        String hash = hash(token);
        JSONArray list = clients(), kept = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            if (!hash.equals(list.optJSONObject(i).optString("hash"))) kept.put(list.opt(i));
        }
        prefs.edit().putString("clients", kept.toString()).apply();
        notifyChanged(null);
    }

    synchronized void forgetAll() {
        prefs.edit().remove("clients").apply();
        notifyChanged(null);
    }

    private JSONArray clients() {
        try {
            return new JSONArray(prefs.getString("clients", "[]"));
        } catch (Exception exception) {
            return new JSONArray();
        }
    }

    private void notifyChanged(@Nullable String paired) {
        main.post(() -> {
            for (Listener listener : listeners) listener.onPairingChanged(paired);
        });
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(Locale.US, "%02x", b));
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
