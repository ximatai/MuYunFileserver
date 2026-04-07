package net.ximatai.muyun.fileserver.application;

import net.ximatai.muyun.fileserver.api.dto.FileMetadataResponse;
import net.ximatai.muyun.fileserver.domain.file.FileMetadata;

final class FileMetadataMapper {

    private FileMetadataMapper() {
    }

    static FileMetadataResponse toResponse(FileMetadata metadata) {
        return new FileMetadataResponse(
                metadata.id(),
                metadata.tenantId(),
                metadata.originalFilename(),
                metadata.extension(),
                metadata.mimeType(),
                metadata.sizeBytes(),
                metadata.sha256(),
                metadata.status().name(),
                metadata.temporary(),
                metadata.remark(),
                metadata.uploadedBy(),
                metadata.uploadedAt(),
                metadata.deletedAt()
        );
    }
}
