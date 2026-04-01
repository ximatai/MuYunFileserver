package net.ximatai.muyun.fileserver.api.dto;

import java.time.Instant;

public record UploadFileItemResponse(
        String id,
        String originalFilename,
        String mimeType,
        long sizeBytes,
        Instant uploadedAt
) {
}
