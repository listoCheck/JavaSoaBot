import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CodexAnalyzerTest {
    @TempDir Path temp;
    CodexAnalyzer analyzer(String mode, Duration timeout) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        String classPath = Path.of("target/test-classes").toAbsolutePath().toString();
        return new CodexAnalyzer(List.of(java, "-cp", classPath, FakeCodex.class.getName(), mode), temp, "", timeout);
    }
    @Test void passesImageAndUnicodePromptAndCleansUp() throws Exception {
        String answer = analyzer("ok", Duration.ofSeconds(10)).analyze(new byte[]{1,2,3}, "Вопросы со скриншота");
        assertEquals("Ответ: 42", answer);
        try (var files = Files.list(temp)) { assertEquals(0, files.count()); }
    }
    @Test void nonzeroExitDoesNotReturnAnAnswer() throws Exception {
        assertThrows(IOException.class, () -> analyzer("fail", Duration.ofSeconds(10)).analyze(new byte[]{1}, "test"));
        try (var files = Files.list(temp)) { assertEquals(0, files.count()); }
    }
    @Test void timeoutStopsChildAndCleansUp() throws Exception {
        Exception failure = assertThrows(IOException.class, () -> analyzer("sleep", Duration.ofMillis(800)).analyze(new byte[]{1}, "test"));
        try (var files = Files.list(temp)) { assertEquals(0, files.count(), failure.toString()); }
    }
    @Test void emptyAnswerIsRejected() {
        assertThrows(IOException.class, () -> analyzer("empty", Duration.ofSeconds(10)).analyze(new byte[]{1}, "test"));
    }
    @Test void commandHasReadOnlyEphemeralAndNoShell() {
        List<String> args = analyzer("ok", Duration.ofSeconds(10)).command(temp.resolve("a.png"), temp.resolve("out.txt"));
        assertTrue(args.contains("read-only")); assertTrue(args.contains("--ephemeral"));
        assertTrue(args.contains("--ignore-user-config")); assertTrue(args.contains("forced_login_method=\"chatgpt\""));
        assertTrue(args.contains("shell_tool")); assertEquals("-", args.get(args.size()-1));
        assertFalse(args.contains("--dangerously-bypass-approvals-and-sandbox"));
    }
    public static class FakeCodex {
        public static void main(String[] args) throws Exception {
            String mode = args[0];
            if (mode.equals("fail")) System.exit(2);
            if (mode.equals("sleep")) Thread.sleep(30_000);
            List<String> options = Arrays.asList(args);
            Path image = Path.of(options.get(options.indexOf("--image") + 1));
            Path output = Path.of(options.get(options.indexOf("--output-last-message") + 1));
            if (!Files.isRegularFile(image)) System.exit(3);
            String prompt = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            if (mode.equals("ok") && !prompt.contains("Вопросы со скриншота")) System.exit(4);
            if (System.getenv("OPENAI_API_KEY") != null || System.getenv("CODEX_API_KEY") != null) System.exit(5);
            Files.writeString(output, mode.equals("empty") ? " " : "Ответ: 42", StandardCharsets.UTF_8);
        }
    }
}
