import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;

/** Selection starts at the pointer immediately; a single left click finishes it. */
final class ScreenshotSelector {
    private static boolean active;
    static void open(Consumer<String> error) {
        open(error, null);
    }
    static void open(Consumer<String> error, Consumer<BufferedImage> completed) {
        EventQueue.invokeLater(() -> {
            if (active) return;
            active = true;
            try { new Selection(completed).show(); }
            catch (Exception e) { active = false; error.accept("Could not capture the screen. Try Win+Shift+S."); }
        });
    }
    static Rectangle rectangle(Point anchor, Point end, Rectangle desktop) {
        return new Rectangle(Math.min(anchor.x, end.x), Math.min(anchor.y, end.y),
            Math.abs(end.x - anchor.x), Math.abs(end.y - anchor.y)).intersection(desktop);
    }
    static BufferedImage crop(BufferedImage desktopImage, Rectangle desktop, Rectangle selection) {
        Rectangle clipped = desktop.intersection(selection);
        if (clipped.width <= 0 || clipped.height <= 0) throw new IllegalArgumentException("Empty selection");
        BufferedImage result = new BufferedImage(clipped.width, clipped.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.drawImage(desktopImage, 0, 0, clipped.width, clipped.height, clipped.x - desktop.x, clipped.y - desktop.y,
            clipped.x - desktop.x + clipped.width, clipped.y - desktop.y + clipped.height, null); g.dispose();
        return result;
    }
    static final class ImageTransfer implements Transferable {
        final BufferedImage image;
        ImageTransfer(BufferedImage image) { this.image = image; }
        public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }
        public boolean isDataFlavorSupported(DataFlavor flavor) { return DataFlavor.imageFlavor.equals(flavor); }
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
            return image;
        }
    }
    private static final class Selection {
        final List<JDialog> windows = new ArrayList<>();
        final Rectangle desktop;
        final BufferedImage screenshot;
        final Point anchor;
        Point end;
        boolean finished;
        final Timer timeout;
        final Consumer<BufferedImage> completed;
        Selection(Consumer<BufferedImage> completed) throws Exception {
            this.completed = completed;
            anchor = MouseInfo.getPointerInfo().getLocation(); end = new Point(anchor);
            GraphicsDevice[] devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
            Rectangle bounds = new Rectangle(devices[0].getDefaultConfiguration().getBounds());
            for (GraphicsDevice device : devices) bounds = bounds.union(device.getDefaultConfiguration().getBounds());
            desktop = bounds;
            if ((long) bounds.width * bounds.height > 40_000_000) throw new IOException("Desktop is too large");
            screenshot = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_RGB);
            Graphics2D capture = screenshot.createGraphics();
            try {
                for (GraphicsDevice device : devices) {
                    Rectangle screen = device.getDefaultConfiguration().getBounds();
                    BufferedImage image = new Robot(device).createScreenCapture(screen);
                    capture.drawImage(image, screen.x - bounds.x, screen.y - bounds.y, screen.width, screen.height, null);
                }
            } finally { capture.dispose(); }
            timeout = new Timer(120_000, e -> close()); timeout.setRepeats(false);
        }
        void show() {
            try {
                JDialog focus = null;
                for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                    GraphicsConfiguration config = device.getDefaultConfiguration();
                    Rectangle screen = config.getBounds();
                    JDialog window = new JDialog((Frame) null, "Screenshot selection", false, config); windows.add(window);
                    window.setUndecorated(true);
                    window.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { close(); } });
                    window.setAlwaysOnTop(true); window.setBounds(screen); window.setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                    JPanel panel = new JPanel() {
                        @Override protected void paintComponent(Graphics graphics) {
                            Graphics2D g = (Graphics2D) graphics.create();
                            try {
                                g.drawImage(screenshot, desktop.x - screen.x, desktop.y - screen.y, null);
                                g.setColor(new Color(0, 0, 0, 115)); g.fillRect(0, 0, getWidth(), getHeight());
                                Rectangle area = rectangle(anchor, end, desktop);
                                Rectangle local = new Rectangle(area.x - screen.x, area.y - screen.y, area.width, area.height);
                                Shape oldClip = g.getClip(); g.clip(local);
                                g.drawImage(screenshot, desktop.x - screen.x, desktop.y - screen.y, null); g.setClip(oldClip);
                                g.setColor(new Color(40, 180, 255)); g.setStroke(new BasicStroke(2));
                                g.drawRect(local.x, local.y, Math.max(0, local.width - 1), Math.max(0, local.height - 1));
                                g.setColor(new Color(0, 0, 0, 200)); g.fillRoundRect(12, 12, 490, 35, 10, 10);
                                g.setColor(Color.WHITE); g.setFont(new Font("Dialog", Font.PLAIN, 14));
                                g.drawString("Двигайте мышь · ЛКМ — снимок · Esc / ПКМ — отмена", 24, 35);
                            } finally { g.dispose(); }
                        }
                    };
                    MouseAdapter mouse = new MouseAdapter() {
                        @Override public void mouseMoved(MouseEvent e) { move(e.getLocationOnScreen()); }
                        @Override public void mouseDragged(MouseEvent e) { move(e.getLocationOnScreen()); }
                        @Override public void mousePressed(MouseEvent e) { move(e.getLocationOnScreen()); }
                        @Override public void mouseReleased(MouseEvent e) {
                            if (e.getButton() == MouseEvent.BUTTON3) { close(); return; }
                            if (e.getButton() != MouseEvent.BUTTON1) return;
                            move(e.getLocationOnScreen());
                            Rectangle area = rectangle(anchor, end, desktop);
                            if (area.width < 2 || area.height < 2) return;
                            BufferedImage image = crop(screenshot, desktop, area);
                            close();
                            if (completed != null) completed.accept(image); else copyToClipboard(image);
                        }
                    };
                    panel.addMouseListener(mouse); panel.addMouseMotionListener(mouse);
                    panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
                    panel.getActionMap().put("cancel", new AbstractAction() { public void actionPerformed(ActionEvent e) { close(); } });
                    window.setContentPane(panel); window.setVisible(true);
                    if (screen.contains(anchor)) focus = window;
                }
                if (focus != null) { focus.toFront(); focus.requestFocus(); }
                timeout.start();
            } catch (RuntimeException e) { close(); throw e; }
        }
        void move(Point point) { end = point; windows.forEach(Component::repaint); }
        void close() {
            if (finished) return;
            finished = true; timeout.stop(); windows.forEach(Window::dispose); active = false;
        }
        void copyToClipboard(BufferedImage image) {
            Timer retry = new Timer(100, null);
            int[] attempts = {0};
            retry.addActionListener(e -> {
                try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new ImageTransfer(image), null); retry.stop(); }
                catch (IllegalStateException busy) {
                    if (++attempts[0] >= 10) { retry.stop(); ClipboardToPhone.notifyUser("Clipboard is busy. Please take the screenshot again."); }
                }
            });
            retry.setInitialDelay(0); retry.start();
        }
    }
}
