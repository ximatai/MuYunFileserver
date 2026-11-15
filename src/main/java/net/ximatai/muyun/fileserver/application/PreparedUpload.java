package net.ximatai.muyun.fileserver.application;

import net.ximatai.muyun.fileserver.application.storage.AssetStorage;
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
        AssetStorage.Location storageLocation,
        String storageKey,
        Instant uploadedAt,
        boolean temporary,
        Path tempFile,
        Integer imageWidth,
        Integer imageHeight
) {
    FileMetadata toMetadata(String remark, String uploadedBy) {
        return new FileMetadata(
                fileId,
                tenantId,
                originalFilename,
                extension,
                mimeType,
                sizeBytes,
                sha256,
                storageLocation.type(),
                storageLocation.bucket(),
                storageKey,
                storageLocation.id(),
                FileStatus.ACTIVE,
                temporary,
                uploadedBy,
                uploadedAt,
                null,
                null,
                remark,
                imageWidth,
                imageHeight
        );
    }
}
