import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

final class PromptSettings {
    final Path file;
    volatile String current;
    PromptSettings(Path file, String initial) throws IOException {
        this.file = file;
        current = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : initial;
    }
    synchronized void set(String text) throws IOException {
        if (text == null || text.isBlank() || text.length() > 4000) throw new IllegalArgumentException("Prompt must contain 1–4000 characters");
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, text.strip(), StandardCharsets.UTF_8);
        try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        current = text.strip();
    }
}
