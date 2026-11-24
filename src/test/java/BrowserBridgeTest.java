import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;
class BrowserBridgeTest {
    @TempDir Path temp;
    @Test void requiresKeyAndReturnsLatestAnswerWithoutCaching() throws Exception {
        BrowserBridge bridge = new BrowserBridge(temp, 0); bridge.server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + bridge.server.getAddress().getPort() + "/answer");
            HttpClient client = HttpClient.newHttpClient();
            assertEquals(403, client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            bridge.publish("Ответ", "1. Ответ: 42");
            var response = client.send(HttpRequest.newBuilder(uri).header("X-Bridge-Key", bridge.key).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode()); assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("1. Ответ: 42", ScreenshotService.JSON.readTree(response.body()).path("text").asText());
            bridge.publish("Обработка", ""); assertEquals("", ScreenshotService.JSON.readTree(bridge.snapshot()).path("text").asText());
        } finally { bridge.server.stop(0); }
    }
}
