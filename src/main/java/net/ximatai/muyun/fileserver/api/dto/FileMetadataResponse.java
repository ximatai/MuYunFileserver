package net.ximatai.muyun.fileserver.api.dto;

import java.time.Instant;

public record FileMetadataResponse(
        String id,
        String tenantId,
        String originalFilename,
        String extension,
        String mimeType,
        long sizeBytes,
        String sha256,
        String status,
        boolean temporary,
        String remark,
        String uploadedBy,
        Instant uploadedAt,
        Instant deletedAt
) {
}
