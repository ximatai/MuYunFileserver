package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.api.dto.FileMetadataResponse;
import net.ximatai.muyun.fileserver.api.dto.FileViewResponse;
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

    @Inject
    RenderedPdfService renderedPdfService;

    @Inject
    ViewDescriptorService viewDescriptorService;

    @Inject
    TextViewService textViewService;

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
        return FileMetadataMapper.toResponse(metadata);
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
                () -> storageProvider.open(metadata.storageKey()),
                (start, length) -> storageProvider.openRange(metadata.storageKey(), start, length)
        );
    }

    public RenderedPdfResolution openRenderedPdf(String fileId) {
        FileMetadata metadata = requireAccessibleFile(fileId);

        RenderedPdfResolution renderedPdf = renderedPdfService.openRenderedPdf(metadata);

        RequestContext requestContext = requestContextHolder.getRequired();
        LOG.info(OperationLog.format(
                "rendered_pdf",
                "success",
                "file_id", fileId,
                "tenant_id", requestContext.tenantId(),
                "user_id", requestContext.userId(),
                "request_id", requestContext.requestId(),
                "storage_provider", metadata.storageProvider()
        ));

        return renderedPdf;
    }

    public void ensureRenderedPdfReady(String fileId) {
        FileMetadata metadata = requireAccessibleFile(fileId);
        renderedPdfService.ensureRenderedPdfReady(metadata);

        RequestContext requestContext = requestContextHolder.getRequired();
        LOG.info(OperationLog.format(
                "rendered_pdf_ready",
                "success",
                "file_id", fileId,
                "tenant_id", requestContext.tenantId(),
                "user_id", requestContext.userId(),
                "request_id", requestContext.requestId(),
                "storage_provider", metadata.storageProvider()
        ));
    }

    public FileViewResponse getView(String fileId) {
        FileMetadata metadata = requireAccessibleFile(fileId);
        FileViewResponse descriptor = viewDescriptorService.describeInternal(metadata);

        RequestContext requestContext = requestContextHolder.getRequired();
        LOG.info(OperationLog.format(
                "view_descriptor",
                "success",
                "file_id", fileId,
                "tenant_id", requestContext.tenantId(),
                "user_id", requestContext.userId(),
                "request_id", requestContext.requestId(),
                "storage_provider", metadata.storageProvider(),
                "viewer_type", descriptor.viewerType().value()
        ));
        return descriptor;
    }

    public DownloadFile openViewContent(String fileId) {
        FileMetadata metadata = requireAccessibleFile(fileId);
        ViewerType viewerType = viewDescriptorService.resolveViewerType(metadata.mimeType());
        RequestContext requestContext = requestContextHolder.getRequired();
        if (viewerType == ViewerType.PDF) {
            RenderedPdfResolution renderedPdf = renderedPdfService.openRenderedPdf(metadata);
            LOG.info(OperationLog.format(
                    "view_content",
                    "success",
                    "file_id", fileId,
                    "tenant_id", requestContext.tenantId(),
                    "user_id", requestContext.userId(),
                    "request_id", requestContext.requestId(),
                    "storage_provider", metadata.storageProvider(),
                    "viewer_type", viewerType.value()
            ));
            return new DownloadFile(
                    fileId,
                    renderedPdf.originalFilename(),
                    renderedPdf.mimeType(),
                    renderedPdf.sizeBytes(),
                    renderedPdf.inputStreamSupplier(),
                    renderedPdf.rangeInputStreamSupplier()
            );
        }
        if (viewerType == ViewerType.IMAGE || viewerType == ViewerType.VIDEO || viewerType == ViewerType.AUDIO) {
            if (!storageProvider.exists(metadata.storageKey())) {
                throw new NotFoundException("file not found");
            }
            LOG.info(OperationLog.format(
                    "view_content",
                    "success",
                    "file_id", fileId,
                    "tenant_id", requestContext.tenantId(),
                    "user_id", requestContext.userId(),
                    "request_id", requestContext.requestId(),
                    "storage_provider", metadata.storageProvider(),
                    "viewer_type", viewerType.value()
            ));
            return new DownloadFile(
                    metadata.id(),
                    metadata.originalFilename(),
                    metadata.mimeType(),
                    metadata.sizeBytes(),
                    () -> storageProvider.open(metadata.storageKey()),
                    (start, length) -> storageProvider.openRange(metadata.storageKey(), start, length)
            );
        }
        if (viewerType == ViewerType.TEXT) {
            LOG.info(OperationLog.format(
                    "view_content",
                    "success",
                    "file_id", fileId,
                    "tenant_id", requestContext.tenantId(),
                    "user_id", requestContext.userId(),
                    "request_id", requestContext.requestId(),
                    "storage_provider", metadata.storageProvider(),
                    "viewer_type", viewerType.value()
            ));
            return textViewService.openTextContent(metadata);
        }
        throw new NotFoundException("view content is not available for current file");
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
