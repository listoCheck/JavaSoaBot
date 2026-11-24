import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.JOptionPane;

public class ClipboardToPhone {
    static final String APP_NAME = "Amuz Razer sound";
    static volatile boolean paused;
    static TrayIcon tray;
    static final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(10);
    static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    static String token, chat;
    static FileChannel lockChannel;
    static FileLock lock;
    static Path appDir;
    static ScreenshotService service;
    static TelegramMenu menu;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            BufferedImage sample = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
            sample.setRGB(1, 1, 0xff123456);
            byte[] png = encode(sample);
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
            if (decoded.getRGB(1, 1) != 0xff123456 || !Arrays.equals(hash(png), hash(encode(sample))))
                throw new AssertionError("PNG roundtrip failed");
            sample.setRGB(1, 1, 0xff654321);
            if (Arrays.equals(hash(png), hash(encode(sample)))) throw new AssertionError("Change detection failed");
            System.out.println("PASS: PNG roundtrip, stable hash, changed image detection");
            return;
        }
        Path base = Path.of(System.getProperty("app.dir", ".")).toAbsolutePath().normalize();
        appDir = base;
        Properties config = new Properties();
        if (!Files.exists(base.resolve("config.properties"))) {
            Files.copy(base.resolve("config.example.properties"), base.resolve("config.properties"));
        }
        try (var in = Files.newBufferedReader(base.resolve("config.properties"), StandardCharsets.UTF_8)) { config.load(in); }
        if (args.length > 0 && args[0].equals("--codex-test")) {
            BufferedImage sample = new BufferedImage(900, 250, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = sample.createGraphics(); g.setColor(Color.WHITE); g.fillRect(0, 0, 900, 250);
            g.setColor(Color.BLACK); g.setFont(new Font("Arial", Font.BOLD, 48));
            g.drawString("1) 17 + 25 = ?", 30, 90); g.drawString("2) 6 * 7 = ?", 30, 180); g.dispose();
            System.out.println(new CodexAnalyzer(base, config).analyze(encode(sample), ScreenshotService.DEFAULT_PROMPT));
            return;
        }
        token = config.getProperty("bot.token", "").trim();
        chat = config.getProperty("chat.id", "").trim();
        if (!token.matches("[0-9]+:[A-Za-z0-9_-]+") || !chat.matches("[0-9]+")) {
            JOptionPane.showMessageDialog(null, "Set bot.token and chat.id in config.properties first.", APP_NAME, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) apiKey = config.getProperty("openai.api.key", "").trim();
        boolean aiEnabled = Boolean.parseBoolean(config.getProperty("ai.enabled", "true"));
        String provider = config.getProperty("ai.provider", "codex").trim();
        if (!provider.equals("codex") && !provider.equals("openai")) throw new IOException("ai.provider must be codex or openai");
        if (aiEnabled && provider.equals("openai") && apiKey.isBlank()) {
            JOptionPane.showMessageDialog(null, "Set openai.api.key in config.properties (or OPENAI_API_KEY), or set ai.enabled=false.");
            return;
        }
        String prompt = ScreenshotService.DEFAULT_PROMPT;
        String promptFile = config.getProperty("ai.prompt.file", "").trim();
        if (!promptFile.isEmpty()) prompt = Files.readString(base.resolve(promptFile), StandardCharsets.UTF_8);
        PromptSettings promptSettings = new PromptSettings(base.resolve(promptFile.isEmpty() ? "prompt.txt" : promptFile), prompt);
        prompt = promptSettings.current;
        service = new ScreenshotService(token, chat, apiKey, config.getProperty("openai.model", "gpt-4.1-mini").trim(), prompt, aiEnabled);
        if (aiEnabled && provider.equals("codex")) {
            try { service.codex = new CodexAnalyzer(base, config); }
            catch (IOException e) { JOptionPane.showMessageDialog(null, "Codex CLI not found. Set codex.executable in config.properties."); return; }
        }
        lockChannel = FileChannel.open(base.resolve("app.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = lockChannel.tryLock();
        if (lock == null) { JOptionPane.showMessageDialog(null, APP_NAME + " is already running.", APP_NAME, JOptionPane.INFORMATION_MESSAGE); return; }
        service.loadHistory(base.resolve("work").resolve("messages-" + token.substring(0, token.indexOf(':')) + "-" + chat + ".json"));
        service.warning = ClipboardToPhone::notifyUser;
        menu = new TelegramMenu(service, promptSettings, base.resolve("work").resolve("updates-" + token.substring(0, token.indexOf(':')) + "-" + chat + ".txt"));
        if (!SystemTray.isSupported()) { JOptionPane.showMessageDialog(null, "Windows system tray is unavailable."); return; }
        try {
            service.browser = new BrowserBridge(base.resolve("work"), 17843);
            service.browser.start();
        } catch (Exception e) { JOptionPane.showMessageDialog(null, "Browser panel unavailable: port 17843 is busy or access was denied.", APP_NAME, JOptionPane.WARNING_MESSAGE); }
        run();
    }

    static void run() throws Exception {
        EventQueue.invokeAndWait(() -> {
            BufferedImage icon = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = icon.createGraphics();
            g.setColor(new Color(35, 140, 210)); g.fillRoundRect(2, 2, 28, 28, 8, 8);
            g.setColor(Color.WHITE); g.drawString("RS", 7, 21); g.dispose();
            PopupMenu menu = new PopupMenu();
            CheckboxMenuItem pause = new CheckboxMenuItem("Pause");
            pause.addItemListener(e -> { paused = pause.getState(); tray.setToolTip(APP_NAME + (paused ? ": paused" : ": active")); });
            menu.add(pause);
            MenuItem exit = new MenuItem("Exit"); exit.addActionListener(e -> System.exit(0)); menu.add(exit);
            tray = new TrayIcon(icon, APP_NAME + ": active", menu); tray.setImageAutoSize(true);
            try { SystemTray.getSystemTray().add(tray); } catch (AWTException e) { throw new RuntimeException(e); }
        });
        startScreenshotHotkey();
        menu.start();
        Thread sender = new Thread(() -> {
            while (true) {
                try {
                    byte[] bytes = queue.take();
                    while (paused) Thread.sleep(300);
                    service.process(bytes);
                } catch (InterruptedException e) { return; }
                catch (Exception e) {
                    if (service.browser != null) service.browser.publish("Ошибка доставки", "Проверьте Telegram и интернет. Повторите скриншот.");
                    notifyUser("Screenshot or answer delivery failed. Check Internet and Telegram settings. Copy text, then the image again to retry.");
                }
            }
        }, "telegram-sender");
        sender.setDaemon(true); sender.start();
        notifyUser("Active: clipboard images go to Telegram and, if enabled, AI. Left or Right arrow: screenshot. Right-click: pause or exit.");
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        byte[] previous = null;
        boolean initialized = false;
        while (true) {
            try {
                byte[] current = null;
                byte[] png = null;
                if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                    Image image = (Image) clipboard.getData(DataFlavor.imageFlavor);
                    png = encode(image); current = hash(png);
                }
                // Establish a baseline at launch; never send existing clipboard contents.
                if (initialized && !paused && current != null && !Arrays.equals(previous, current)) {
                    if (png.length > 19_000_000) notifyUser("Image is too large to send (limit: 19 MB).");
                    else if (!queue.offer(png)) notifyUser("Queue is full. This image was skipped.");
                }
                previous = current;
                initialized = true;
            } catch (IllegalStateException | IOException | UnsupportedFlavorException e) {
                // Windows may temporarily lock the clipboard; try again next tick.
            }
            Thread.sleep(750);
        }
    }

    static void startScreenshotHotkey() {
        try {
            Path base = appDir;
            Process helper = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-ExecutionPolicy", "Bypass", "-File", base.resolve("ScreenshotHotkey.ps1").toString(),
                "-ParentProcessId", Long.toString(ProcessHandle.current().pid()))
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Runtime.getRuntime().addShutdownHook(new Thread(helper::destroy));
            Thread listener = new Thread(() -> {
                try (var reader = helper.inputReader()) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.equals("SNIP") || paused) continue;
                        ScreenshotSelector.open(ClipboardToPhone::notifyUser);
                    }
                    notifyUser("Left/Right arrow helper stopped. Restart the application to restore the shortcuts.");
                } catch (Exception e) { notifyUser("Screenshot shortcut unavailable. Win+Shift+S can still be used."); }
            }, "screenshot-hotkey");
            listener.setDaemon(true); listener.start();
        } catch (Exception e) { notifyUser("Screenshot shortcut unavailable. Win+Shift+S can still be used."); }
    }

    static byte[] encode(Image image) throws IOException {
        int w = image.getWidth(null), h = image.getHeight(null);
        if (w < 1 || h < 1 || (long) w * h > 40_000_000) throw new IOException("Unsupported image dimensions");
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics(); g.drawImage(image, 0, 0, null); g.dispose();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(out, "png", buffer);
        return buffer.toByteArray();
    }
    static byte[] hash(byte[] bytes) throws Exception { return MessageDigest.getInstance("SHA-256").digest(bytes); }
    static void notifyUser(String text) { EventQueue.invokeLater(() -> tray.displayMessage(APP_NAME, text, TrayIcon.MessageType.INFO)); }
}
