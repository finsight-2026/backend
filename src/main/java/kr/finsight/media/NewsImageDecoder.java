package kr.finsight.media;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class NewsImageDecoder {
    static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final int MAX_SIDE = 4096;
    record Image(String mime, int width, int height, byte[] bytes) {}

    private NewsImageDecoder() {}

    static Image decode(InputStream source, String declaredMime) {
        try (var stream = new MemoryCacheImageInputStream(source)) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            BufferedImage decoded;
            try {
                var detected = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                    case "jpeg", "jpg" -> "image/jpeg";
                    case "png" -> "image/png";
                    case "webp" -> "image/webp";
                    default -> throw invalid();
                };
                if (declaredMime != null && !declaredMime.isBlank() && !"application/octet-stream".equalsIgnoreCase(declaredMime)
                        && !detected.equalsIgnoreCase(declaredMime)) throw invalid();
                reader.setInput(stream, false, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || width > MAX_SIDE || height < 1 || height > MAX_SIDE) throw invalid();
                var warning = new AtomicBoolean();
                reader.addIIOReadWarningListener((ignored, message) -> warning.set(true));
                decoded = reader.read(0);
                if (warning.get() || decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                    throw invalid();
                }
                return reencode(decoded, detected);
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException | NegativeArraySizeException invalidImage) {
            throw invalid();
        }
    }

    private static Image reencode(BufferedImage decoded, String detected) throws IOException {
        boolean alpha = decoded.getColorModel().hasAlpha();
        var pixels = new BufferedImage(decoded.getWidth(), decoded.getHeight(),
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var graphics = pixels.createGraphics();
        try {
            graphics.drawImage(decoded, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        boolean png = alpha || "image/png".equals(detected);
        var bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(pixels, png ? "png" : "jpeg", bytes)) throw new IllegalStateException("Image writer unavailable");
        return new Image(png ? "image/png" : "image/jpeg", pixels.getWidth(), pixels.getHeight(), bytes.toByteArray());
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "이미지 형식이나 크기를 확인해 주세요.");
    }
}
