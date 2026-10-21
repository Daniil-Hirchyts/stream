package tv.daniil.cast;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Answers the Mac's "is a Daniil Cast TV here?" broadcast with this TV's name, identity and HTTP port. Nothing is
 * revealed that the TV's info page doesn't already show.
 */
final class Discovery {
    static final String PROBE = "DANIILCAST_DISCOVER/1";

    private final Supplier<String> answer;
    @Nullable private DatagramSocket socket;

    Discovery(Supplier<String> answer) {
        this.answer = answer;
    }

    void start(int port) throws IOException {
        DatagramSocket server = new DatagramSocket(null);
        server.setReuseAddress(true);
        server.setBroadcast(true);
        server.bind(new InetSocketAddress(port));
        socket = server;
        Thread thread = new Thread(() -> listen(server), "cast-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        if (socket != null) socket.close();
    }

    private void listen(DatagramSocket server) {
        byte[] buffer = new byte[256];
        while (!server.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                server.receive(packet);
                String probe = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.US_ASCII).trim();
                if (!PROBE.equals(probe)) continue;
                byte[] reply = answer.get().getBytes(StandardCharsets.UTF_8);
                server.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress()));
            } catch (IOException exception) {
                if (server.isClosed()) return;
            }
        }
    }
}
