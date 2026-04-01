package net.ximatai.muyun.fileserver.application;

import net.ximatai.muyun.fileserver.domain.file.FileMetadata;
import net.ximatai.muyun.fileserver.domain.file.FileStatus;

import java.nio.file.Path;
import java.time.Instant;

record PreparedUpload(
        String fileId,
        String tenantId,
        String originalFilename,
        String extension,
        String mimeType,
        long sizeBytes,
        String sha256,
        String storageKey,
        Instant uploadedAt,
        Path tempFile
) {
    FileMetadata toMetadata(String remark, String uploadedBy, String storageProvider) {
        return new FileMetadata(
                fileId,
                tenantId,
                originalFilename,
                extension,
                mimeType,
                sizeBytes,
                sha256,
                storageProvider,
                null,
                storageKey,
                FileStatus.ACTIVE,
                uploadedBy,
                uploadedAt,
                null,
                null,
                remark
        );
    }
}
