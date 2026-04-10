package net.ximatai.muyun.fileserver.application;

public record PreviewResolution(
        String originalFilename,
        String mimeType,
        long sizeBytes,
        java.io.InputStream inputStream
) {
}
