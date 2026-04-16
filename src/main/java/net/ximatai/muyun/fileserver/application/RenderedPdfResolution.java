package net.ximatai.muyun.fileserver.application;

public record RenderedPdfResolution(
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
