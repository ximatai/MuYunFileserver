package net.ximatai.muyun.fileserver.application;

import net.ximatai.muyun.fileserver.common.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ImageFileFactsTest {
    @TempDir Path temporary;

    @Test
    void extractsEncodedCanvasDimensionsAcrossSupportedFormats() throws Exception {
        var samples = Map.of("sample.png", "image/png", "sample.jpg", "image/jpeg", "sample.gif", "image/gif",
                "lossy.webp", "image/webp", "lossless.webp", "image/webp", "extended.webp", "image/webp");
        for (var sample : samples.entrySet()) {
            Path path = Path.of(getClass().getResource("/image-dimensions/" + sample.getKey()).toURI());
            var facts = ImageFileFacts.read(path, sample.getValue());
            assertEquals(16, facts.width());
            assertEquals(8, facts.height());
        }
    }

    @Test
    void rejectsTruncatedRasterMetadata() throws Exception {
        Path path = temporary.resolve("broken.png");
        Files.write(path, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
        assertThrows(ValidationException.class, () -> ImageFileFacts.read(path, "image/png"));
    }

    @Test
    void nonRasterFormatsDoNotAcquireInventedDimensions() {
        assertNull(ImageFileFacts.read(temporary.resolve("document.pdf"), "application/pdf"));
        assertNull(ImageFileFacts.read(temporary.resolve("vector.svg"), "image/svg+xml"));
    }
}
