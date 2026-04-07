package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.api.dto.DeleteFileResult;
import net.ximatai.muyun.fileserver.common.exception.ForbiddenException;
import net.ximatai.muyun.fileserver.common.exception.NotFoundException;
import net.ximatai.muyun.fileserver.common.exception.ValidationException;
import net.ximatai.muyun.fileserver.common.log.OperationLog;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;
import net.ximatai.muyun.fileserver.domain.file.FileMetadata;
import net.ximatai.muyun.fileserver.domain.file.FileStatus;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FileMetadataRepository;
import net.ximatai.muyun.fileserver.infrastructure.ulid.UlidGenerator;
import org.jboss.logging.Logger;

import java.time.Instant;

@ApplicationScoped
public class TokenFileCommandService {

    private static final Logger LOG = Logger.getLogger(TokenFileCommandService.class);
    private static final String DELETE_PURPOSE = "delete";

    @Inject
    DownloadTokenVerifier downloadTokenVerifier;

    @Inject
    FileMetadataRepository repository;

    @Inject
    UlidGenerator ulidGenerator;

    @Inject
    FileServiceConfig config;

    public DeleteFileResult delete(String fileId, String accessToken) {
        if (!config.token().enabled()) {
            throw new NotFoundException("resource not found");
        }
        if (!ulidGenerator.isValid(fileId)) {
            throw new ValidationException("invalid fileId");
        }

        DownloadTokenClaims claims = downloadTokenVerifier.verify(accessToken);
        if (!DELETE_PURPOSE.equals(claims.purpose())) {
            throw new ForbiddenException("download token purpose is not valid for delete");
        }
        if (!fileId.equals(claims.fileId())) {
            throw new ForbiddenException("download token does not match requested file");
        }

        FileMetadata metadata = repository.findById(fileId)
                .orElseThrow(() -> new NotFoundException("file not found"));

        if (metadata.status() == FileStatus.DELETED) {
            throw new NotFoundException("file not found");
        }
        if (!metadata.tenantId().equals(claims.tenantId())) {
            throw new ForbiddenException("download token is not valid for current tenant");
        }

        Instant deletedAt = Instant.now();
        boolean deleted = repository.softDelete(fileId, metadata.tenantId(), claims.subject(), deletedAt);
        if (!deleted) {
            throw new NotFoundException("file not found");
        }

        LOG.info(OperationLog.format(
                "delete_by_token",
                "success",
                "file_id", fileId,
                "tenant_id", metadata.tenantId(),
                "request_id", null,
                "storage_provider", metadata.storageProvider(),
                "token_issuer", claims.issuer(),
                "token_subject", claims.subject()
        ));

        return new DeleteFileResult(fileId, FileStatus.DELETED.name(), deletedAt);
    }
}
