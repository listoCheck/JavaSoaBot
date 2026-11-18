import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Only the configured private-chat owner can read or change the prompt. */
final class TelegramMenu {
    final ScreenshotService service;
    final PromptSettings settings;
    final Path offsetFile;
    boolean editing;
    Instant editUntil = Instant.MIN;
    TelegramMenu(ScreenshotService service, PromptSettings settings, Path offsetFile) {
        this.service = service; this.settings = settings; this.offsetFile = offsetFile;
    }
    void start() {
        Thread worker = new Thread(this::poll, "telegram-menu"); worker.setDaemon(true); worker.start();
    }
    JsonNode updates(long offset, int timeout) throws Exception {
        String json = ScreenshotService.JSON.writeValueAsString(Map.of("offset", offset, "timeout", timeout, "allowed_updates", new String[]{"message"}));
        return service.checked(HttpRequest.newBuilder(URI.create(service.telegramBase + "getUpdates"))
            .timeout(Duration.ofSeconds(timeout + 15)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)).build(), true).path("result");
    }
    void poll() {
        long offset = 0;
        boolean initialized = false;
        while (!initialized && !Thread.currentThread().isInterrupted()) {
            try {
                if (Files.exists(offsetFile)) offset = Long.parseLong(Files.readString(offsetFile).strip());
                else {
                    // First launch: do not execute old commands left in Telegram's queue.
                    for (JsonNode update : updates(-1, 0)) offset = update.path("update_id").asLong() + 1;
                    persist(offset);
                }
                registerCommands(); initialized = true;
            } catch (Exception e) {
                service.warning.accept("Telegram menu initialization failed. Retrying in 5 seconds.");
                try { Thread.sleep(5000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
        while (!Thread.currentThread().isInterrupted()) {
            try {
                for (JsonNode update : updates(offset, 25)) {
                    long next = update.path("update_id").asLong() + 1;
                    if (next <= offset) continue;
                    // Acknowledge locally before handling so a restart cannot replay edits.
                    persist(next); offset = next;
                    try { handle(update); }
                    catch (Exception e) { service.warning.accept("Could not handle a Telegram menu command. Try the command again."); }
                }
            } catch (Exception e) {
                service.warning.accept("Telegram menu is unavailable. Check Internet; only one bot instance may poll updates.");
                try { Thread.sleep(5000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }
    void registerCommands() throws Exception {
        var body = ScreenshotService.JSON.createObjectNode();
        body.putObject("scope").put("type", "chat").put("chat_id", service.chat);
        var commands = body.putArray("commands");
        String[][] items = {{"menu", "Настройки ответов"}, {"prompt", "Показать промпт"}, {"edit_prompt", "Изменить промпт"},
            {"reset_prompt", "Восстановить стандартный промпт"}, {"status", "Модель и режим"}, {"cancel", "Отменить изменение"}};
        for (String[] item : items) commands.addObject().put("command", item[0]).put("description", item[1]);
        service.checked(HttpRequest.newBuilder(URI.create(service.telegramBase + "setMyCommands")).timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(ScreenshotService.JSON.writeValueAsBytes(body))).build(), true);
    }
    void persist(long offset) throws Exception {
        Files.createDirectories(offsetFile.getParent());
        Path temp = offsetFile.resolveSibling(offsetFile.getFileName() + ".tmp");
        Files.writeString(temp, Long.toString(offset));
        try { Files.move(temp, offsetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp, offsetFile, StandardCopyOption.REPLACE_EXISTING); }
    }
    void handle(JsonNode update) throws Exception {
        JsonNode msg = update.path("message");
        if (!msg.path("chat").path("type").asText().equals("private")
            || !msg.path("chat").path("id").asText().equals(service.chat)
            || !msg.path("from").path("id").asText().equals(service.chat)
            || msg.path("from").path("is_bot").asBoolean() || !msg.path("text").isTextual()) return;
        String text = msg.path("text").asText().strip();
        String command = text.startsWith("/") ? text.split("\\s+", 2)[0].split("@", 2)[0].toLowerCase() : text;
        if (command.equals("/cancel") || text.equals("Отмена")) {
            editing = false; say("Изменение промпта отменено."); return;
        }
        if (command.equals("/menu") || command.equals("/start")) {
            editing = false; say("Настройки ответов. Выберите действие кнопками ниже. Изменения применяются к следующей обработке скриншота."); return;
        }
        if (command.equals("/prompt") || text.equals("Показать промпт")) {
            for (String part : ScreenshotService.split(settings.current, 3500)) say(part); return;
        }
        if (command.equals("/edit_prompt") || text.equals("Изменить промпт")) {
            editing = true; editUntil = Instant.now().plusSeconds(300);
            say("Отправьте новый промпт одним текстовым сообщением (до 4000 символов). Для отмены — /cancel. Ожидание 5 минут."); return;
        }
        if (command.equals("/reset_prompt") || text.equals("Сбросить промпт")) {
            settings.set(ScreenshotService.DEFAULT_PROMPT); service.prompt = settings.current; editing = false;
            say("Восстановлен стандартный промпт. Он будет использован при следующей обработке скриншота."); return;
        }
        if (command.equals("/status") || text.equals("Модель и режим")) {
            say(service.codex == null ? "Провайдер OpenAI API; модель: " + service.model
                : "Модель: " + service.codex.model + "\nРежим рассуждения: " + service.codex.reasoning + "\nКаждый скриншот — отдельный сеанс."); return;
        }
        if (text.startsWith("/")) { say("Неизвестная команда. /menu — настройки, /cancel — отмена."); return; }
        if (!editing) return;
        if (Instant.now().isAfter(editUntil)) { editing = false; say("Время ожидания истекло. Нажмите «Изменить промпт» ещё раз."); return; }
        if (text.isBlank() || text.length() > 4000) { say("Нужен текст длиной от 1 до 4000 символов. Повторите или отправьте /cancel."); return; }
        settings.set(text); service.prompt = settings.current; editing = false;
        say("Промпт сохранён. Следующий скриншот будет обработан с новой инструкцией; перезапуск не нужен.");
    }
    void say(String text) throws Exception { service.menuMessage(text, true); }
}
