import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Read-only, authenticated loopback endpoint; never exposes bot or login credentials. */
final class BrowserBridge {
    final HttpServer server;
    final String key;
    volatile String state = "Ожидание скриншота", text = "";
    volatile long revision;
    BrowserBridge(Path directory, int port) throws Exception {
        Files.createDirectories(directory);
        Path keyFile = directory.resolve("browser-key.txt");
        if (!Files.exists(keyFile)) Files.writeString(keyFile, UUID.randomUUID().toString());
        key = Files.readString(keyFile).strip();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/answer", exchange -> {
            try {
                if (!exchange.getRequestMethod().equals("GET")) { exchange.sendResponseHeaders(405, -1); return; }
                if (!key.equals(exchange.getRequestHeaders().getFirst("X-Bridge-Key"))) { exchange.sendResponseHeaders(403, -1); return; }
                byte[] bytes = snapshot().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
    }
    synchronized void publish(String state, String text) { this.state = state; this.text = text; revision++; }
    synchronized String snapshot() throws java.io.IOException {
        return ScreenshotService.JSON.writeValueAsString(Map.of("state", state, "text", text, "revision", revision));
    }
    void start() { server.start(); Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0))); }
}
