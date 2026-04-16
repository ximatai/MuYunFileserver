package net.ximatai.muyun.fileserver.application;

import java.nio.file.Path;

public record RenderedPdfConversionResult(
        Path outputFile,
        long sizeBytes,
        String sha256
) {
}
