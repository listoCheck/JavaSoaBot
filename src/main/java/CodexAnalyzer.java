import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Uses the installed Codex CLI and its ChatGPT login; never reads authentication files. */
final class CodexAnalyzer {
    final List<String> executable;
    final Path workRoot;
    final String model;
    String reasoning = "low";
    final Duration timeout;
    CodexAnalyzer(Path appDir, Properties config) throws IOException {
        this(List.of(resolveExecutable(config.getProperty("codex.executable", ""))), appDir.resolve("work"),
            config.getProperty("codex.model", "gpt-6.1-sol").isBlank() ? "gpt-6.1-sol" : config.getProperty("codex.model", "gpt-6.1-sol").trim(), Duration.ofSeconds(180));
        reasoning = config.getProperty("codex.reasoning", "low").trim();
        if (!List.of("low", "medium", "high", "xhigh", "max").contains(reasoning)) throw new IOException("Invalid codex.reasoning");
    }
    CodexAnalyzer(List<String> executable, Path workRoot, String model, Duration timeout) {
        this.executable = List.copyOf(executable); this.workRoot = workRoot; this.model = model; this.timeout = timeout;
    }
    static String resolveExecutable(String configured) throws IOException {
        if (!configured.isBlank()) {
            Path path = Path.of(configured).toAbsolutePath();
            if (!Files.isRegularFile(path) || !path.toString().toLowerCase(Locale.ROOT).endsWith(".exe"))
                throw new IOException("codex.executable must point to codex.exe");
            return path.toString();
        }
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            Path npm = Path.of(appData, "npm", "node_modules", "@openai", "codex");
            if (Files.isDirectory(npm)) {
                try (var paths = Files.find(npm, 10, (p, a) -> a.isRegularFile() && p.getFileName().toString().equalsIgnoreCase("codex.exe"))) {
                    var found = paths.findFirst(); if (found.isPresent()) return found.get().toString();
                }
            }
        }
        for (String directory : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            Path exe = Path.of(directory, "codex.exe");
            if (Files.isRegularFile(exe)) return exe.toString();
        }
        throw new IOException("Codex CLI not found. Install Codex or set codex.executable.");
    }
    List<String> command(Path image, Path output) {
        List<String> args = new ArrayList<>(executable);
        args.addAll(List.of("exec", "--ignore-user-config", "--ephemeral", "--skip-git-repo-check", "--sandbox", "read-only",
            "-c", "forced_login_method=\"chatgpt\"", "-c", "web_search=\"disabled\"", "-c", "project_doc_max_bytes=0"));
        for (String feature : List.of("shell_tool", "unified_exec", "apps", "plugins", "hooks", "multi_agent", "computer_use", "browser_use", "in_app_browser"))
            args.addAll(List.of("--disable", feature));
        if (!model.isEmpty()) args.addAll(List.of("--model", model));
        args.addAll(List.of("-c", "model_reasoning_effort=\"" + reasoning + "\""));
        args.addAll(List.of("--image", image.toAbsolutePath().toString(), "--output-last-message", output.toAbsolutePath().toString(), "--color", "never", "-"));
        return args;
    }
    String analyze(byte[] png, String prompt) throws Exception {
        Files.createDirectories(workRoot);
        Path job = Files.createTempDirectory(workRoot, "capture-");
        Path image = job.resolve("screenshot.png"), output = job.resolve("answer.txt");
        Process process = null;
        Thread cleanupHook = null;
        try {
            Files.write(image, png);
            ProcessBuilder builder = new ProcessBuilder(command(image, output)).directory(job.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().remove("OPENAI_API_KEY"); builder.environment().remove("CODEX_API_KEY");
            builder.environment().remove("OPENAI_BASE_URL");
            process = builder.start();
            Process running = process;
            cleanupHook = new Thread(() -> stop(running));
            Runtime.getRuntime().addShutdownHook(cleanupHook);
            try (var writer = process.outputWriter(StandardCharsets.UTF_8)) {
                writer.write(prompt + "\nОтветь только по приложенному изображению. Не используй инструменты, команды, файлы или интернет. Верни только ответ для пользователя.");
            }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) throw new IOException("Codex timed out");
            if (process.exitValue() != 0) throw new IOException("Codex exited with code " + process.exitValue());
            if (!Files.isRegularFile(output) || Files.size(output) > 1_000_000) throw new IOException("Codex answer missing or too large");
            String answer = Files.readString(output, StandardCharsets.UTF_8).strip();
            if (answer.isEmpty()) throw new IOException("Codex returned an empty answer");
            return answer;
        } finally {
            if (process != null) {
                stop(process);
                process.getInputStream().close(); process.getErrorStream().close(); process.getOutputStream().close();
            }
            if (cleanupHook != null) Runtime.getRuntime().removeShutdownHook(cleanupHook);
            // Only the three known paths of this generated job; no recursive deletion.
            deleteTemporary(image); deleteTemporary(output); deleteTemporary(job);
        }
    }
    static void deleteTemporary(Path path) throws IOException {
        // Windows can briefly retain the terminated child's working-directory handle.
        for (int attempt = 0; ; attempt++) {
            try { Files.deleteIfExists(path); return; }
            catch (FileSystemException e) {
                if (attempt >= 20) throw e;
                try { Thread.sleep(50); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
            }
        }
    }
    static void stop(Process process) {
        if (process.isAlive()) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
