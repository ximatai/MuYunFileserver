package net.ximatai.muyun.fileserver.domain.file;

import java.time.Instant;

public record FileMetadata(
        String id,
        String tenantId,
        String originalFilename,
        String extension,
        String mimeType,
        long sizeBytes,
        String sha256,
        String storageProvider,
        String storageBucket,
        String storageKey,
        FileStatus status,
        String uploadedBy,
        Instant uploadedAt,
        Instant deletedAt,
        String deleteMarkedBy,
        String remark
) {
}
