package net.ximatai.muyun.fileserver.application;

import java.nio.file.Path;

public record PreviewConversionResult(
        Path outputFile,
        long sizeBytes,
        String sha256
) {
}
