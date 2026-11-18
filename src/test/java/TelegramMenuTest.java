import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TelegramMenuTest {
    @TempDir Path temp;
    RecordingService service;
    PromptSettings settings;
    TelegramMenu menu;
    static class RecordingService extends ScreenshotService {
        final List<String> messages = new ArrayList<>();
        RecordingService() { super(HttpClient.newHttpClient(), "http://localhost/", URI.create("http://localhost/"), "567", "", "test", DEFAULT_PROMPT, true); }
        @Override synchronized void menuMessage(String text, boolean keyboard) { assertTrue(keyboard); messages.add(text); }
    }
    @BeforeEach void setup() throws Exception {
        service = new RecordingService(); settings = new PromptSettings(temp.resolve("prompt.txt"), ScreenshotService.DEFAULT_PROMPT);
        menu = new TelegramMenu(service, settings, temp.resolve("offset.txt"));
    }
    JsonNode message(String text, long chat, long user, String type) {
        return ScreenshotService.JSON.valueToTree(Map.of("message", Map.of("chat", Map.of("id", chat, "type", type),
            "from", Map.of("id", user, "is_bot", false), "text", text)));
    }
    void send(String text) throws Exception { menu.handle(message(text, 567, 567, "private")); }
    @Test void menuCanChangePromptAndPersistItWithoutRestart() throws Exception {
        send("/menu"); send("Изменить промпт"); send("Отвечай кратко. Сохраняй нумерацию.");
        assertEquals("Отвечай кратко. Сохраняй нумерацию.", service.prompt);
        assertEquals(service.prompt, new PromptSettings(settings.file, "fallback").current);
        assertFalse(menu.editing); assertEquals(3, service.messages.size());
        send("Показать промпт"); assertEquals(service.prompt, service.messages.get(3));
    }
    @Test void otherChatsAndGroupsCannotReadOrChangePrompt() throws Exception {
        send("/edit_prompt");
        menu.handle(message("steal", 999, 999, "private"));
        menu.handle(message("steal", 567, 999, "private"));
        menu.handle(message("/prompt", 567, 567, "group"));
        assertEquals(ScreenshotService.DEFAULT_PROMPT, settings.current);
        assertEquals(1, service.messages.size()); assertTrue(menu.editing);
    }
    @Test void cancelAndExpiryDoNotSaveText() throws Exception {
        send("/edit_prompt"); send("/cancel"); send("ignored");
        assertFalse(Files.exists(settings.file));
        send("/edit_prompt"); menu.editUntil = Instant.MIN; send("expired");
        assertFalse(Files.exists(settings.file)); assertFalse(menu.editing);
    }
    @Test void resetRestoresBuiltInPrompt() throws Exception {
        send("/edit_prompt"); send("custom"); send("/reset_prompt");
        assertEquals(ScreenshotService.DEFAULT_PROMPT, service.prompt);
        assertEquals(service.prompt, Files.readString(settings.file));
    }
    @Test void unknownCommandAndOversizedTextCannotBecomePrompt() throws Exception {
        send("/edit_prompt"); send("/unknown"); send("x".repeat(4001));
        assertTrue(menu.editing); assertFalse(Files.exists(settings.file));
        send("valid"); assertEquals("valid", service.prompt);
    }
    @Test void plainTextDoesNotChangePromptOutsideEditMode() throws Exception {
        send("ordinary message"); assertTrue(service.messages.isEmpty()); assertFalse(Files.exists(settings.file));
    }
    @Test void statusShowsPinnedModelAndEffort() throws Exception {
        service.codex = new CodexAnalyzer(List.of("fake.exe"), temp, "gpt-6.1-sol", java.time.Duration.ofSeconds(1));
        send("/status"); assertTrue(service.messages.get(0).contains("gpt-6.1-sol")); assertTrue(service.messages.get(0).contains("low"));
    }
    @Test void updateOffsetPersistsAcrossRestart() throws Exception {
        menu.persist(12345); assertEquals("12345", Files.readString(menu.offsetFile));
    }
}
