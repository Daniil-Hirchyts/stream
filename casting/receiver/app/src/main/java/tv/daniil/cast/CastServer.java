package tv.daniil.cast;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A small HTTP/1.1 server for JSON requests on the local network. One request per connection; bodies are capped
 * so a stray client can't exhaust memory.
 */
final class CastServer {
    interface Handler {
        Response handle(Request request) throws Exception;
    }

    static final class Request {
        final String method, path;
        final Map<String, String> headers;
        final byte[] body;

        Request(String method, String path, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }

        JSONObject json() throws Exception {
            return body.length == 0 ? new JSONObject() : new JSONObject(new String(body, StandardCharsets.UTF_8));
        }

        @Nullable String bearer() {
            String value = headers.get("authorization");
            return value != null && value.startsWith("Bearer ") ? value.substring(7).trim() : null;
        }
    }

    static final class Response {
        final int status;
        final JSONObject body;

        Response(int status, JSONObject body) {
            this.status = status;
            this.body = body;
        }
    }

    /** A failure with an HTTP status and a short code the Mac can act on, such as "unauthorized". */
    static final class Failure extends Exception {
        final int status;
        final String code;

        Failure(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    private static final int MAX_HEADER = 16 * 1024;
    private static final int MAX_BODY = 4 * 1024 * 1024;
    private static final int TIMEOUT_MS = 15_000;

    private final Handler handler;
    private final ExecutorService pool = new ThreadPoolExecutor(1, 8, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());
    @Nullable private ServerSocket socket;

    CastServer(Handler handler) {
        this.handler = handler;
    }

    /** Listens on the first free port of {@code first}…{@code first + 4} and returns it. */
    int start(int first) throws IOException {
        IOException last = null;
        for (int port = first; port < first + 5; port++) {
            try {
                ServerSocket server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(port));
                socket = server;
                Thread thread = new Thread(this::accept, "cast-server");
                thread.setDaemon(true);
                thread.start();
                return port;
            } catch (IOException exception) {
                last = exception;
            }
        }
        throw last;
    }

    void stop() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) { /* Closing anyway. */ }
        pool.shutdownNow();
    }

    private void accept() {
        ServerSocket server = socket;
        while (server != null && !server.isClosed()) {
            try {
                Socket client = server.accept();
                client.setSoTimeout(TIMEOUT_MS);
                pool.execute(() -> serve(client));
            } catch (IOException exception) {
                if (server.isClosed()) return;
            }
        }
    }

    private void serve(Socket client) {
        try (Socket connection = client) {
            InputStream in = new BufferedInputStream(connection.getInputStream());
            Response response;
            try {
                response = handler.handle(read(in));
            } catch (Failure failure) {
                response = error(failure.status, failure.code, failure.getMessage());
            } catch (Exception exception) {
                response = error(400, "bad-request", exception.getMessage() == null
                        ? exception.getClass().getSimpleName() : exception.getMessage());
            }
            write(connection.getOutputStream(), response);
        } catch (IOException ignored) {
            // The client went away; nothing to answer.
        }
    }

    static Response error(int status, String code, String message) {
        try {
            return new Response(status, new JSONObject().put("ok", false)
                    .put("error", new JSONObject().put("code", code).put("message", message)));
        } catch (Exception exception) {
            return new Response(status, new JSONObject());
        }
    }

    private static Request read(InputStream in) throws Exception {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        // The headers end at the first blank line: the last four bytes read are \r\n\r\n.
        for (int recent = 0; recent != 0x0D0A0D0A;) {
            int b = in.read();
            if (b < 0) throw new IOException("Connection closed");
            head.write(b);
            if (head.size() > MAX_HEADER) throw new Failure(431, "too-large", "Headers too large");
            recent = recent << 8 | b;
        }
        String[] lines = head.toString("ISO-8859-1").split("\r\n");
        String[] start = lines[0].split(" ");
        if (start.length < 3) throw new Failure(400, "bad-request", "Malformed request");
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US), lines[i].substring(colon + 1).trim());
            }
        }
        int length = 0;
        if (headers.containsKey("content-length")) length = Integer.parseInt(headers.get("content-length"));
        if (length < 0 || length > MAX_BODY) throw new Failure(413, "too-large", "Request body too large");
        byte[] body = new byte[length];
        for (int read = 0; read < length;) {
            int count = in.read(body, read, length - read);
            if (count < 0) throw new IOException("Connection closed");
            read += count;
        }
        String path = start[1];
        int query = path.indexOf('?');
        return new Request(start[0].toUpperCase(Locale.US), query < 0 ? path : path.substring(0, query), headers, body);
    }

    private static void write(OutputStream out, Response response) throws IOException {
        byte[] body = response.body.toString().getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + response.status + " " + reason(response.status) + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 409: return "Conflict";
            case 413: return "Payload Too Large";
            case 429: return "Too Many Requests";
            default: return status >= 500 ? "Server Error" : "Error";
        }
    }
}
