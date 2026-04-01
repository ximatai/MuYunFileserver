package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.api.dto.FileMetadataResponse;
import net.ximatai.muyun.fileserver.common.context.RequestContext;
import net.ximatai.muyun.fileserver.common.context.RequestContextHolder;
import net.ximatai.muyun.fileserver.common.exception.ForbiddenException;
import net.ximatai.muyun.fileserver.common.exception.NotFoundException;
import net.ximatai.muyun.fileserver.common.exception.StorageException;
import net.ximatai.muyun.fileserver.common.exception.ValidationException;
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
        LOG.infof("metadata query success fileId=%s tenantId=%s userId=%s requestId=%s",
                fileId, requestContext.tenantId(), requestContext.userId(), requestContext.requestId());
        return new FileMetadataResponse(
                metadata.id(),
                metadata.tenantId(),
                metadata.originalFilename(),
                metadata.extension(),
                metadata.mimeType(),
                metadata.sizeBytes(),
                metadata.sha256(),
                metadata.storageProvider(),
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
            throw new StorageException("stored file content is missing");
        }

        RequestContext requestContext = requestContextHolder.getRequired();
        LOG.infof("download success fileId=%s tenantId=%s userId=%s requestId=%s",
                fileId, requestContext.tenantId(), requestContext.userId(), requestContext.requestId());

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
