package net.ximatai.muyun.fileserver.application;

public record PreviewResolution(
        String originalFilename,
        String mimeType,
        long sizeBytes,
        InputStreamSupplier inputStreamSupplier,
        RangeInputStreamSupplier rangeInputStreamSupplier
) {

    public boolean supportsRange() {
        return rangeInputStreamSupplier != null;
    }
}
