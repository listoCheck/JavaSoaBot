import org.junit.jupiter.api.Test;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class ScreenshotSelectorTest {
    @Test void selectionWorksInAllFourDirections() {
        Rectangle desktop = new Rectangle(-100, -100, 400, 400);
        for (Point end : new Point[]{new Point(70,80),new Point(30,80),new Point(70,20),new Point(30,20)}) {
            Rectangle area = ScreenshotSelector.rectangle(new Point(50,50), end, desktop);
            assertEquals(20, area.width); assertEquals(30, area.height);
            assertEquals(Math.min(50,end.x), area.x); assertEquals(Math.min(50,end.y), area.y);
        }
    }
    @Test void cropUsesDesktopOffsetAndOriginalPixelsWithoutOverlay() {
        Rectangle desktop = new Rectangle(-100, -50, 200, 100);
        BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        image.setRGB(25,30,0xff123456);
        BufferedImage result = ScreenshotSelector.crop(image, desktop, new Rectangle(-80,-30,20,20));
        assertEquals(20, result.getWidth()); assertEquals(20, result.getHeight());
        assertEquals(0xff123456, result.getRGB(5,10));
        result.setRGB(5,10,0); assertEquals(0xff123456,image.getRGB(25,30));
    }
    @Test void clipsSelectionAtDesktopBoundsAndRejectsEmptyCapture() {
        Rectangle desktop = new Rectangle(0,0,100,100);
        assertEquals(new Rectangle(0,0,50,60), ScreenshotSelector.rectangle(new Point(-20,-20),new Point(50,60),desktop));
        assertThrows(IllegalArgumentException.class, () -> ScreenshotSelector.crop(new BufferedImage(100,100,1),desktop,new Rectangle(0,0,0,20)));
    }
    @Test void clipboardExposesAnImageAndRejectsTextFlavor() throws Exception {
        BufferedImage image = new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB);
        var transfer = new ScreenshotSelector.ImageTransfer(image);
        assertSame(image,transfer.getTransferData(DataFlavor.imageFlavor));
        assertFalse(transfer.isDataFlavorSupported(DataFlavor.stringFlavor));
        assertThrows(UnsupportedFlavorException.class, () -> transfer.getTransferData(DataFlavor.stringFlavor));
    }
}
