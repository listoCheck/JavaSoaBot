import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class ScreenshotServiceTest {
    HttpServer server;
    String base;
    List<String> calls;
    List<JsonNode> replies;
    volatile String aiReply;
    volatile int aiStatus;
    volatile boolean telegramFails;
    volatile JsonNode aiRequest;
    @TempDir Path temp;
    List<JsonNode> deletions;
    volatile boolean deletionFails;
    volatile long undeletable;
    final Map<String, JsonNode> requests = new java.util.concurrent.ConcurrentHashMap<>();
    java.util.concurrent.atomic.AtomicLong nextId;
    @BeforeEach void setup() throws Exception {
        calls = new CopyOnWriteArrayList<>(); replies = new CopyOnWriteArrayList<>();
        deletions = new CopyOnWriteArrayList<>(); nextId = new java.util.concurrent.atomic.AtomicLong(123);
        deletionFails = false;
        undeletable = 0;
        aiStatus = 200; telegramFails = false;
        aiReply = "{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\"},{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"1. Ответ: 42\\n2. Ответ: да\"}]}]}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath(); calls.add(path);
            byte[] request = exchange.getRequestBody().readAllBytes();
            if (!path.equals("/sendDocument")) requests.put(path, ScreenshotService.JSON.readTree(request));
            String response; int status = 200;
            if (path.equals("/sendDocument")) {
                assertTrue(new String(request, StandardCharsets.UTF_8).contains("image/png"));
                response = telegramFails ? "{\"ok\":false}" : "{\"ok\":true,\"result\":{\"message_id\":" + nextId.getAndIncrement() + "}}";
            } else if (path.equals("/responses")) {
                assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
                aiRequest = ScreenshotService.JSON.readTree(request); response = aiReply; status = aiStatus;
            } else if (path.equals("/getUpdates")) {
                response = "{\"ok\":true,\"result\":[{\"update_id\":900}]}";
            } else if (path.equals("/setMyCommands")) {
                response = "{\"ok\":true,\"result\":true}";
            } else if (path.equals("/deleteMessages")) {
                JsonNode deletion = ScreenshotService.JSON.readTree(request); deletions.add(deletion);
                boolean blocked = deletionFails;
                for (JsonNode id : deletion.path("message_ids")) if (id.asLong() == undeletable) blocked = true;
                response = blocked ? "{\"ok\":false}" : "{\"ok\":true}";
            } else {
                replies.add(ScreenshotService.JSON.readTree(request)); response = "{\"ok\":true,\"result\":{\"message_id\":" + nextId.getAndIncrement() + "}}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }
    @AfterEach void cleanup() { server.stop(0); }
    ScreenshotService service(boolean ai) {
        return new ScreenshotService(HttpClient.newHttpClient(), base, URI.create(base + "responses"), "567", "test-key", "test-model", ScreenshotService.DEFAULT_PROMPT, ai);
    }
    @Test void imageThenAnalysisThenReplyWithOriginalImageAndMessageId() throws Exception {
        byte[] image = {1, 2, 3, 4}; service(true).process(image);
        assertEquals(List.of("/sendDocument", "/responses", "/sendMessage"), calls);
        assertFalse(aiRequest.path("store").asBoolean(true));
        assertEquals("data:image/png;base64,AQIDBA==", aiRequest.at("/input/0/content/1/image_url").asText());
        assertEquals("high", aiRequest.at("/input/0/content/1/detail").asText());
        assertEquals("1. Ответ: 42\n2. Ответ: да", replies.get(0).path("text").asText());
        assertEquals(123, replies.get(0).at("/reply_parameters/message_id").asInt());
        assertFalse(replies.get(0).has("parse_mode"));
    }
    @Test void aiErrorProducesReadableReplyWithoutLeakingResponse() throws Exception {
        aiStatus = 401; aiReply = "secret test-key"; service(true).process(new byte[]{1});
        String message = replies.get(0).path("text").asText();
        assertTrue(message.contains("API-ключ")); assertFalse(message.contains("test-key"));
    }
    @Test void failedImageDoesNotCallPaidModel() {
        telegramFails = true; assertThrows(Exception.class, () -> service(true).process(new byte[]{1}));
        assertEquals(List.of("/sendDocument"), calls);
    }
    @Test void aiCanBeDisabled() throws Exception {
        service(false).process(new byte[]{1}); assertEquals(List.of("/sendDocument"), calls);
    }
    @Test void longAnswerSplitsWithoutBreakingEmoji() throws Exception {
        String answer = "x".repeat(3499) + "😀" + "y".repeat(4000);
        aiReply = ScreenshotService.JSON.writeValueAsString(Map.of("output", List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", answer))))));
        service(true).process(new byte[]{1});
        StringBuilder joined = new StringBuilder();
        for (JsonNode reply : replies) {
            String text = reply.path("text").asText(); assertTrue(text.length() <= 3500);
            assertFalse(Character.isHighSurrogate(text.charAt(text.length() - 1))); joined.append(text);
        }
        assertEquals(answer, joined.toString()); assertEquals(3, replies.size());
    }
    @Test void incompleteAnswerIsMarked() throws Exception {
        aiReply = aiReply.replace("completed", "incomplete"); service(true).process(new byte[]{1});
        assertTrue(replies.get(0).path("text").asText().contains("Ответ неполный"));
    }
    @Test void browserAndTelegramReceiveSameAnswerFromOneModelRequest() throws Exception {
        ScreenshotService service = service(true); service.browser = new BrowserBridge(temp, 0);
        try {
            service.process(new byte[]{1});
            assertEquals(replies.get(0).path("text").asText(), ScreenshotService.JSON.readTree(service.browser.snapshot()).path("text").asText());
            assertEquals(1, calls.stream().filter(path -> path.equals("/responses")).count());
        } finally { service.browser.server.stop(0); }
    }
    @Test void newScreenshotDeletesPreviousPhotoAndAllAnswerPartsFirst() throws Exception {
        ScreenshotService service = service(true);
        aiReply = ScreenshotService.JSON.writeValueAsString(Map.of("output", List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", "x".repeat(8000)))))));
        service.process(new byte[]{1});
        assertEquals(List.of(123L,124L,125L,126L), service.sentMessages);
        calls.clear(); service.process(new byte[]{2});
        assertEquals("/deleteMessages", calls.get(0)); assertEquals("/sendDocument", calls.get(1));
        assertEquals("[123,124,125,126]", deletions.get(0).path("message_ids").toString());
        assertEquals("567", deletions.get(0).path("chat_id").asText());
        assertEquals(List.of(127L,128L,129L,130L), service.sentMessages);
    }
    @Test void remembersMessagesAcrossRestartsAndTracksErrorReplies() throws Exception {
        Path history = temp.resolve("messages.json");
        ScreenshotService first = service(true); first.loadHistory(history);
        aiStatus = 401; first.process(new byte[]{1});
        ScreenshotService restarted = service(false); restarted.loadHistory(history); restarted.process(new byte[]{2});
        assertEquals("[123,124]", deletions.get(0).path("message_ids").toString());
        ScreenshotService third = service(false); third.loadHistory(history);
        assertEquals(List.of(125L), third.sentMessages);
    }
    @Test void deletionFailureKeepsIdsForRetryAndStillSendsNewScreenshot() throws Exception {
        ScreenshotService service = service(false); service.process(new byte[]{1});
        List<String> warnings = new ArrayList<>(); service.warning = warnings::add;
        deletionFails = true; service.process(new byte[]{2});
        assertEquals(List.of(123L,124L), service.sentMessages); assertEquals(1, warnings.size());
        deletionFails = false; service.process(new byte[]{3});
        assertEquals("[123,124]", deletions.get(1).path("message_ids").toString());
        assertEquals(List.of(125L), service.sentMessages);
    }
    @Test void deletionUsesBatchesOfAtMostOneHundred() throws Exception {
        ScreenshotService service = service(false);
        for (long id = 1; id <= 205; id++) service.sentMessages.add(id);
        service.clearPreviousMessages();
        assertEquals(3, deletions.size());
        assertEquals(100, deletions.get(0).path("message_ids").size());
        assertEquals(100, deletions.get(1).path("message_ids").size());
        assertEquals(5, deletions.get(2).path("message_ids").size());
        assertTrue(service.sentMessages.isEmpty());
    }
    @Test void undeletableOldMessageDoesNotBlockDeletionOfRecentMessages() throws Exception {
        ScreenshotService service = service(false);
        service.sentMessages.addAll(List.of(1L,2L,3L)); undeletable = 2;
        service.clearPreviousMessages();
        assertEquals(List.of(2L), service.sentMessages);
        assertEquals(4, deletions.size());
    }
    @Test void menuKeyboardMessagesAreTrackedForNextScreenshotCleanup() throws Exception {
        ScreenshotService service = service(false); service.menuMessage("Настройки", true);
        assertEquals("Изменить промпт", requests.get("/sendMessage").at("/reply_markup/keyboard/0/1").asText());
        service.process(new byte[]{1});
        assertEquals("[123]", deletions.get(0).path("message_ids").toString());
    }
    @Test void menuRegistersCommandsForOwnerChatAndUsesLongPolling() throws Exception {
        ScreenshotService service = service(false);
        TelegramMenu menu = new TelegramMenu(service, new PromptSettings(temp.resolve("prompt.txt"), "initial"), temp.resolve("offset.txt"));
        menu.registerCommands();
        assertEquals("chat", requests.get("/setMyCommands").at("/scope/type").asText());
        assertEquals("567", requests.get("/setMyCommands").at("/scope/chat_id").asText());
        assertEquals(6, requests.get("/setMyCommands").path("commands").size());
        JsonNode updates = menu.updates(899, 25);
        assertEquals(900, updates.get(0).path("update_id").asInt());
        assertEquals(899, requests.get("/getUpdates").path("offset").asInt());
        assertEquals(25, requests.get("/getUpdates").path("timeout").asInt());
    }
}
