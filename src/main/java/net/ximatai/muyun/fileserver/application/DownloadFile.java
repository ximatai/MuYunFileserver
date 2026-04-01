package net.ximatai.muyun.fileserver.application;

import java.io.InputStream;

public record DownloadFile(
        String fileId,
        String originalFilename,
        String mimeType,
        long sizeBytes,
        InputStream inputStream
) {
}
