package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.api.dto.DownloadLinkResponse;
import net.ximatai.muyun.fileserver.common.context.RequestContext;
import net.ximatai.muyun.fileserver.common.context.RequestContextHolder;
import net.ximatai.muyun.fileserver.common.exception.NotFoundException;
import net.ximatai.muyun.fileserver.common.exception.ValidationException;
import net.ximatai.muyun.fileserver.common.log.OperationLog;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;
import net.ximatai.muyun.fileserver.domain.file.FileMetadata;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FileMetadataRepository;
import net.ximatai.muyun.fileserver.infrastructure.ulid.UlidGenerator;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;

@ApplicationScoped
public class DownloadLinkService {

    private static final Logger LOG = Logger.getLogger(DownloadLinkService.class);
    private static final String DOWNLOAD_PURPOSE = "download";

    @Inject
    FileServiceConfig config;

    @Inject
    FileMetadataRepository repository;

    @Inject
    RequestContextHolder requestContextHolder;

    @Inject
    UlidGenerator ulidGenerator;

    @Inject
    DownloadTokenSigner downloadTokenSigner;

    public DownloadLinkResponse create(String fileId, Long expiresInSeconds) {
        if (!config.testConsole().enabled() || !config.token().enabled()) {
            throw new NotFoundException("resource not found");
        }
        if (!ulidGenerator.isValid(fileId)) {
            throw new ValidationException("invalid fileId");
        }

        RequestContext requestContext = requestContextHolder.getRequired();
        FileMetadata metadata = repository.findActiveById(fileId)
                .filter(item -> item.tenantId().equals(requestContext.tenantId()))
                .orElseThrow(() -> new NotFoundException("file not found"));
        Duration ttl = resolveTtl(expiresInSeconds);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        String token = downloadTokenSigner.sign(new DownloadTokenClaims(
                config.token().issuer().orElse(null),
                requestContext.userId(),
                DOWNLOAD_PURPOSE,
                metadata.tenantId(),
                metadata.id(),
                expiresAt.getEpochSecond(),
                now.getEpochSecond(),
                null
        ));

        LOG.info(OperationLog.format(
                "download_link_create",
                "success",
                "file_id", fileId,
                "tenant_id", requestContext.tenantId(),
                "user_id", requestContext.userId(),
                "request_id", requestContext.requestId(),
                "storage_provider", metadata.storageProvider(),
                "expires_at", expiresAt.toString()
        ));
        return new DownloadLinkResponse(
                fileId,
                "/api/v1/public/files/" + fileId + "/download?access_token=" + token,
                expiresAt
        );
    }

    private Duration resolveTtl(Long expiresInSeconds) {
        Duration ttl = expiresInSeconds == null
                ? config.testConsole().defaultDownloadLinkTtl()
                : Duration.ofSeconds(expiresInSeconds);
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(config.testConsole().maxDownloadLinkTtl()) > 0) {
            throw new ValidationException("expiresInSeconds must be between 1 and "
                    + config.testConsole().maxDownloadLinkTtl().toSeconds());
        }
        return ttl;
    }
}
