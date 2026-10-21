package tv.daniil.cast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

import tv.daniil.playback.Capabilities;

/**
 * Keeps the TV reachable from paired Macs while the app is closed: an HTTP API on port 8765 and a discovery
 * responder on the same UDP port. Runs as a foreground service so Fire OS doesn't stop it.
 */
public final class CastService extends Service {
    static final int PORT = 8765;
    static final int PROTOCOL = 1;
    private static final String TAG = "DaniilCast";
    private static final String CHANNEL = "cast";
    private static final long PAIR_REQUEST_INTERVAL_MS = 3000;

    @Nullable private CastServer server;
    @Nullable private Discovery discovery;
    @Nullable private WifiManager.MulticastLock multicast;
    private Pairing pairing;
    private int port = PORT;
    private volatile long lastPairRequest;

    static void start(Context context) {
        context.startForegroundService(new Intent(context, CastService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.service_channel), NotificationManager.IMPORTANCE_MIN));
        startForeground(1, new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_cast)
                .setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.service_text))
                .setOngoing(true).build());
        Capabilities.load(this);
        pairing = Pairing.get(this);
        try {
            server = new CastServer(this::handle);
            port = server.start(PORT);
            discovery = new Discovery(this::announcement);
            discovery.start(PORT);
            // Without this lock some Wi-Fi drivers drop broadcast packets, and the Mac can't find the TV.
            WifiManager wifi = getApplicationContext().getSystemService(WifiManager.class);
            if (wifi != null) {
                multicast = wifi.createMulticastLock("daniil-cast");
                multicast.setReferenceCounted(false);
                multicast.acquire();
            }
        } catch (Exception exception) {
            Log.w(TAG, "Cast server failed to start", exception);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (server != null) server.stop();
        if (discovery != null) discovery.stop();
        if (multicast != null && multicast.isHeld()) multicast.release();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private CastServer.Response handle(CastServer.Request request) throws Exception {
        String route = request.method + " " + request.path;
        switch (route) {
            case "GET /v1/info":
                return ok(info());
            case "POST /v1/pair/request":
                // Anyone on the network may ask the TV to show a code, but not repeatedly.
                long now = SystemClock.elapsedRealtime();
                if (now - lastPairRequest < PAIR_REQUEST_INTERVAL_MS && pairing.activeCode() != null) {
                    return ok(new JSONObject().put("ok", true));
                }
                lastPairRequest = now;
                if (pairing.showCode() == null) {
                    throw new CastServer.Failure(429, "locked", "Too many wrong codes. Try again in a minute.");
                }
                CastHub.showPairing(this);
                return ok(new JSONObject().put("ok", true));
            case "POST /v1/pair": {
                JSONObject body = request.json();
                String client = body.optString("client", "Chrome");
                if (client.length() > 80) client = client.substring(0, 80);
                String token = pairing.pair(body.optString("code"), client);
                if (token == null) {
                    boolean expired = pairing.activeCode() == null;
                    throw new CastServer.Failure(403, expired ? "code-expired" : "wrong-code",
                            expired ? "This code is no longer valid. Ask the TV for a new one." : "That code doesn't match the TV.");
                }
                return ok(new JSONObject().put("ok", true).put("token", token).put("tv", info()));
            }
            default:
                break;
        }
        if (!pairing.authorized(request.bearer())) {
            throw new CastServer.Failure(401, "unauthorized", "This Mac isn't paired with the TV");
        }
        switch (route) {
            case "GET /v1/status":
                return ok(CastHub.status());
            case "POST /v1/command":
                return ok(CastHub.command(this, request.json()));
            case "POST /v1/unpair":
                pairing.revoke(request.bearer());
                return ok(new JSONObject().put("ok", true));
            default:
                throw new CastServer.Failure(404, "not-found", "Unknown request " + route);
        }
    }

    private static CastServer.Response ok(JSONObject body) {
        return new CastServer.Response(200, body);
    }

    private JSONObject info() throws Exception {
        String version = "";
        long code = 0;
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = info.versionName;
            code = info.getLongVersionCode();
        } catch (Exception ignored) { /* Our own package is always there. */ }
        return new JSONObject().put("app", "Daniil Cast").put("protocol", PROTOCOL)
                .put("version", version).put("versionCode", code)
                .put("id", pairing.id()).put("name", deviceName(this)).put("model", Build.MODEL)
                .put("address", address()).put("port", port)
                .put("paired", pairing.clientCount() > 0)
                .put("decoders", Capabilities.json());
    }

    private String announcement() {
        try {
            return info().toString();
        } catch (Exception exception) {
            return "{}";
        }
    }

    /** The name the owner gave the TV in its settings, else its model. */
    static String deviceName(Context context) {
        String name = Settings.Global.getString(context.getContentResolver(), Settings.Global.DEVICE_NAME);
        if (name != null && !name.trim().isEmpty()) return name.trim();
        return Build.MODEL.startsWith("AFT") ? "Fire TV" : Build.MODEL;
    }

    /** This TV's address on the local network, shown so a Mac can be pointed at it by hand. */
    static String pairingAddress() {
        // The emulator's 10.0.2.15 address is behind NAT and cannot be used by
        // Chrome on the host. The simulator launcher forwards the loopback port.
        return "ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE)
                ? "127.0.0.1" : address();
    }

    static String address() {
        try {
            for (NetworkInterface face : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!face.isUp() || face.isLoopback()) continue;
                for (InetAddress address : Collections.list(face.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) return address.getHostAddress();
                }
            }
        } catch (Exception ignored) { /* Offline. */ }
        return "";
    }
}
