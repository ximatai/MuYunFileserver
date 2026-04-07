package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.api.dto.FileMetadataResponse;
import net.ximatai.muyun.fileserver.common.context.RequestContext;
import net.ximatai.muyun.fileserver.common.context.RequestContextHolder;
import net.ximatai.muyun.fileserver.common.exception.ForbiddenException;
import net.ximatai.muyun.fileserver.common.exception.NotFoundException;
import net.ximatai.muyun.fileserver.common.exception.ValidationException;
import net.ximatai.muyun.fileserver.common.log.OperationLog;
import net.ximatai.muyun.fileserver.domain.file.FileMetadata;
import net.ximatai.muyun.fileserver.domain.file.FileStatus;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FileMetadataRepository;
import net.ximatai.muyun.fileserver.infrastructure.storage.StorageProvider;
import net.ximatai.muyun.fileserver.infrastructure.ulid.UlidGenerator;
import org.jboss.logging.Logger;

@ApplicationScoped
public class FileQueryService {

    private static final Logger LOG = Logger.getLogger(FileQueryService.class);

    @Inject
    FileMetadataRepository repository;

    @Inject
    RequestContextHolder requestContextHolder;

    @Inject
    UlidGenerator ulidGenerator;

    @Inject
    StorageProvider storageProvider;

    public FileMetadataResponse getMetadata(String fileId) {
        FileMetadata metadata = requireAccessibleFile(fileId);

        RequestContext requestContext = requestContextHolder.getRequired();
        LOG.info(OperationLog.format(
                "metadata_query",
                "success",
                "file_id", fileId,
                "tenant_id", requestContext.tenantId(),
                "user_id", requestContext.userId(),
                "request_id", requestContext.requestId(),
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

    public DownloadFile openDownload(String fileId) {
        FileMetadata metadata = requireAccessibleFile(fileId);
        if (!storageProvider.exists(metadata.storageKey())) {
            throw new NotFoundException("file not found");
        }

        RequestContext requestContext = requestContextHolder.getRequired();
        LOG.info(OperationLog.format(
                "download",
                "success",
                "file_id", fileId,
                "tenant_id", requestContext.tenantId(),
                "user_id", requestContext.userId(),
                "request_id", requestContext.requestId(),
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

    private FileMetadata requireAccessibleFile(String fileId) {
        if (!ulidGenerator.isValid(fileId)) {
            throw new ValidationException("invalid fileId");
        }

        RequestContext requestContext = requestContextHolder.getRequired();
        FileMetadata metadata = repository.findById(fileId)
                .orElseThrow(() -> new NotFoundException("file not found"));

        if (metadata.status() == FileStatus.DELETED) {
            throw new NotFoundException("file not found");
        }
        if (!metadata.tenantId().equals(requestContext.tenantId())) {
            throw new ForbiddenException("file is not accessible for current tenant");
        }
        return metadata;
    }
}
