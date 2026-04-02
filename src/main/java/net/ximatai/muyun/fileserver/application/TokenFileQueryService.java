package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.api.dto.FileMetadataResponse;
import net.ximatai.muyun.fileserver.common.exception.ForbiddenException;
import net.ximatai.muyun.fileserver.common.exception.NotFoundException;
import net.ximatai.muyun.fileserver.common.exception.StorageException;
import net.ximatai.muyun.fileserver.common.exception.ValidationException;
import net.ximatai.muyun.fileserver.common.log.OperationLog;
import net.ximatai.muyun.fileserver.domain.file.FileMetadata;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FileMetadataRepository;
import net.ximatai.muyun.fileserver.infrastructure.storage.StorageProvider;
import net.ximatai.muyun.fileserver.infrastructure.ulid.UlidGenerator;
import org.jboss.logging.Logger;

@ApplicationScoped
public class TokenFileQueryService {

    private static final Logger LOG = Logger.getLogger(TokenFileQueryService.class);

    @Inject
    DownloadTokenVerifier downloadTokenVerifier;

    @Inject
    FileMetadataRepository repository;

    @Inject
    UlidGenerator ulidGenerator;

    @Inject
    StorageProvider storageProvider;

    public FileMetadataResponse getMetadata(String fileId, String accessToken) {
        FileMetadata metadata = requireAccessibleFile(fileId, accessToken);

        LOG.info(OperationLog.format(
                "metadata_query_by_token",
                "success",
                "file_id", fileId,
                "tenant_id", metadata.tenantId(),
                "request_id", null,
                "storage_provider", metadata.storageProvider()
        ));

        return new FileMetadataResponse(
                metadata.id(),
                metadata.tenantId(),
                metadata.originalFilename(),
                metadata.extension(),
                metadata.mimeType(),
                metadata.sizeBytes(),
                metadata.sha256(),
                metadata.status().name(),
                metadata.remark(),
                metadata.uploadedBy(),
                metadata.uploadedAt(),
                metadata.deletedAt()
        );
    }

    public DownloadFile openDownload(String fileId, String accessToken) {
        FileMetadata metadata = requireAccessibleFile(fileId, accessToken);
        if (!storageProvider.exists(metadata.storageKey())) {
            throw new StorageException("stored file content is missing");
        }

        LOG.info(OperationLog.format(
                "download_by_token",
                "success",
                "file_id", fileId,
                "tenant_id", metadata.tenantId(),
                "request_id", null,
                "storage_provider", metadata.storageProvider()
        ));

        return new DownloadFile(
                metadata.id(),
                metadata.originalFilename(),
                metadata.mimeType(),
                metadata.sizeBytes(),
                storageProvider.open(metadata.storageKey())
        );
    }

    private FileMetadata requireAccessibleFile(String fileId, String accessToken) {
        if (!downloadTokenVerifier.isEnabled()) {
            throw new NotFoundException("resource not found");
        }
        if (!ulidGenerator.isValid(fileId)) {
            throw new ValidationException("invalid fileId");
        }

        DownloadTokenClaims claims = downloadTokenVerifier.verify(accessToken);
        if (!fileId.equals(claims.fileId())) {
            throw new ForbiddenException("download token does not match requested file");
        }

        FileMetadata metadata = repository.findActiveById(fileId)
                .orElseThrow(() -> new NotFoundException("file not found"));

        if (!metadata.tenantId().equals(claims.tenantId())) {
            throw new ForbiddenException("download token is not valid for current tenant");
        }
        return metadata;
    }
}
