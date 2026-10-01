import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Generates clearly fictitious original images for the shared test environment. */
public final class GeneratePhotoFixtures {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(".local/qa-report-photos/fixtures").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        for (int index = 0; index < 2; index++) {
            BufferedImage image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 6; y++) image.setRGB(x, y, index == 0 ? 0x1874d2 : 0x26a86a);
            }
            Path png = directory.resolve("MOP-FAKE-" + (index == 0 ? "blue" : "green") + ".png");
            if (!ImageIO.write(image, "png", png.toFile())) throw new IllegalStateException("PNG writer unavailable");
            if (index == 0 && !ImageIO.write(image, "jpeg", directory.resolve("MOP-FAKE-blue.jpg").toFile())) {
                throw new IllegalStateException("JPEG writer unavailable");
            }
        }
        System.out.println("MOP fictitious photo fixtures generated; no private inputs used.");
    }
}
