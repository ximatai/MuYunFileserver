package net.ximatai.muyun.fileserver.application;

import java.io.InputStream;

public record DownloadFile(
        String fileId,
        String originalFilename,
        String mimeType,
        long sizeBytes,
        InputStreamSupplier inputStreamSupplier,
        RangeInputStreamSupplier rangeInputStreamSupplier
) {

    public DownloadFile(
            String fileId,
            String originalFilename,
            String mimeType,
            long sizeBytes,
            InputStreamSupplier inputStreamSupplier
    ) {
        this(fileId, originalFilename, mimeType, sizeBytes, inputStreamSupplier, null);
    }

    public boolean supportsRange() {
        return rangeInputStreamSupplier != null;
    }
}
