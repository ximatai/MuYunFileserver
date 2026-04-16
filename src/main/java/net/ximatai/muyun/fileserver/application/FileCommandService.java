package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.api.dto.DeleteFileResult;
import net.ximatai.muyun.fileserver.api.dto.FileMetadataResponse;
import net.ximatai.muyun.fileserver.api.dto.PromoteFilesResponse;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@ApplicationScoped
public class FileCommandService {

    private static final Logger LOG = Logger.getLogger(FileCommandService.class);

    @Inject
    FileMetadataRepository repository;

    @Inject
    RequestContextHolder requestContextHolder;

    @Inject
    UlidGenerator ulidGenerator;

    @Inject
    RenderedPdfService renderedPdfService;

    public PromoteFilesResponse promote(List<String> fileIds) {
        List<String> normalizedFileIds = normalizeFileIds(fileIds);
        RequestContext requestContext = requestContextHolder.getRequired();
        List<FileMetadataResponse> items = new ArrayList<>();

        for (String fileId : normalizedFileIds) {
            FileMetadata metadata = repository.findById(fileId)
                    .orElseThrow(() -> new NotFoundException("file not found"));
            ensureAccessible(metadata, requestContext);

            if (metadata.temporary()) {
                repository.promote(fileId, requestContext.tenantId());
                metadata = repository.findById(fileId)
                        .orElseThrow(() -> new NotFoundException("file not found"));
            }
            items.add(FileMetadataMapper.toResponse(metadata));
        }

        LOG.info(OperationLog.format(
                "promote",
                "success",
                "tenant_id", requestContext.tenantId(),
                "user_id", requestContext.userId(),
                "request_id", requestContext.requestId(),
                "file_count", String.valueOf(items.size())
        ));
        return new PromoteFilesResponse(List.copyOf(items));
    }

    public FileMetadataResponse rename(String fileId, String originalFilename) {
        validateFileId(fileId);
        String normalizedFilename = normalizeOriginalFilename(originalFilename);
        String normalizedExtension = extensionOf(normalizedFilename);
        RequestContext requestContext = requestContextHolder.getRequired();
        FileMetadata metadata = repository.findById(fileId)
                .orElseThrow(() -> new NotFoundException("file not found"));

        ensureAccessible(metadata, requestContext);

        if (!repository.rename(fileId, requestContext.tenantId(), normalizedFilename, normalizedExtension)) {
            throw new NotFoundException("file not found");
        }

        FileMetadata renamed = repository.findById(fileId)
                .orElseThrow(() -> new NotFoundException("file not found"));
        LOG.info(OperationLog.format(
                "rename",
                "success",
                "file_id", fileId,
                "tenant_id", requestContext.tenantId(),
                "user_id", requestContext.userId(),
                "request_id", requestContext.requestId(),
                "storage_provider", metadata.storageProvider()
        ));
        return FileMetadataMapper.toResponse(renamed);
    }

    public DeleteFileResult delete(String fileId) {
        validateFileId(fileId);
        RequestContext requestContext = requestContextHolder.getRequired();
        FileMetadata metadata = repository.findById(fileId)
                .orElseThrow(() -> new NotFoundException("file not found"));

        ensureAccessible(metadata, requestContext);

        Instant deletedAt = Instant.now();
        boolean deleted = repository.softDelete(fileId, requestContext.tenantId(), requestContext.userId(), deletedAt);
        if (!deleted) {
            throw new NotFoundException("file not found");
        }
        renderedPdfService.deleteRenderedPdfIfExists(metadata);

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

    private String normalizeOriginalFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new ValidationException("originalFilename must not be blank");
        }
        return originalFilename.trim();
    }

    private String extensionOf(String originalFilename) {
        int index = originalFilename.lastIndexOf('.');
        if (index < 0 || index == originalFilename.length() - 1) {
            return null;
        }
        return originalFilename.substring(index + 1).toLowerCase();
    }

    private List<String> normalizeFileIds(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            throw new ValidationException("fileIds must not be empty");
        }
        LinkedHashSet<String> deduplicated = new LinkedHashSet<>();
        for (String fileId : fileIds) {
            if (fileId == null || fileId.isBlank()) {
                throw new ValidationException("fileIds contains blank value");
            }
            String normalized = fileId.trim();
            validateFileId(normalized);
            deduplicated.add(normalized);
        }
        return List.copyOf(deduplicated);
    }

    private void ensureAccessible(FileMetadata metadata, RequestContext requestContext) {
        if (metadata.status() == FileStatus.DELETED) {
            throw new NotFoundException("file not found");
        }
        if (!metadata.tenantId().equals(requestContext.tenantId())) {
            throw new ForbiddenException("file is not accessible for current tenant");
        }
    }
}
