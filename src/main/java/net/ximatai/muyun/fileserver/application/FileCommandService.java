package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.api.dto.DeleteFileResult;
import net.ximatai.muyun.fileserver.common.context.RequestContext;
import net.ximatai.muyun.fileserver.common.context.RequestContextHolder;
import net.ximatai.muyun.fileserver.common.exception.ForbiddenException;
import net.ximatai.muyun.fileserver.common.exception.NotFoundException;
import net.ximatai.muyun.fileserver.common.exception.ValidationException;
import net.ximatai.muyun.fileserver.common.log.OperationLog;
import net.ximatai.muyun.fileserver.domain.file.FileMetadata;
import net.ximatai.muyun.fileserver.domain.file.FileStatus;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FileMetadataRepository;
import net.ximatai.muyun.fileserver.infrastructure.ulid.UlidGenerator;
import org.jboss.logging.Logger;

import java.time.Instant;

@ApplicationScoped
public class FileCommandService {

    private static final Logger LOG = Logger.getLogger(FileCommandService.class);

    @Inject
    FileMetadataRepository repository;

    @Inject
    RequestContextHolder requestContextHolder;

    @Inject
    UlidGenerator ulidGenerator;

    public DeleteFileResult delete(String fileId) {
        validateFileId(fileId);
        RequestContext requestContext = requestContextHolder.getRequired();
        FileMetadata metadata = repository.findById(fileId)
                .orElseThrow(() -> new NotFoundException("file not found"));

        if (metadata.status() == FileStatus.DELETED) {
            throw new NotFoundException("file not found");
        }
        if (!metadata.tenantId().equals(requestContext.tenantId())) {
            throw new ForbiddenException("file is not accessible for current tenant");
        }

        Instant deletedAt = Instant.now();
        boolean deleted = repository.softDelete(fileId, requestContext.tenantId(), requestContext.userId(), deletedAt);
        if (!deleted) {
            throw new NotFoundException("file not found");
        }

        LOG.info(OperationLog.format(
                "delete",
                "success",
                "file_id", fileId,
                "tenant_id", requestContext.tenantId(),
                "user_id", requestContext.userId(),
                "request_id", requestContext.requestId(),
                "storage_provider", metadata.storageProvider()
        ));
        return new DeleteFileResult(fileId, FileStatus.DELETED.name(), deletedAt);
    }

    private void validateFileId(String fileId) {
        if (!ulidGenerator.isValid(fileId)) {
            throw new ValidationException("invalid fileId");
        }
    }
}
