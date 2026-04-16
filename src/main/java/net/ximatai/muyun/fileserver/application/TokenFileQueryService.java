package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.api.dto.FileMetadataResponse;
import net.ximatai.muyun.fileserver.api.dto.FileViewResponse;
import net.ximatai.muyun.fileserver.common.exception.ForbiddenException;
import net.ximatai.muyun.fileserver.common.exception.NotFoundException;
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

    @Inject
    RenderedPdfService renderedPdfService;

    @Inject
    ViewDescriptorService viewDescriptorService;

    @Inject
    TextViewService textViewService;

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

        return FileMetadataMapper.toResponse(metadata);
    }

    public DownloadFile openDownload(String fileId, String accessToken) {
        FileMetadata metadata = requireAccessibleFile(fileId, accessToken);
        if (!storageProvider.exists(metadata.storageKey())) {
            throw new NotFoundException("file not found");
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
                () -> storageProvider.open(metadata.storageKey()),
                (start, length) -> storageProvider.openRange(metadata.storageKey(), start, length)
        );
    }

    public RenderedPdfResolution openRenderedPdf(String fileId, String accessToken) {
        FileMetadata metadata = requireAccessibleFile(fileId, accessToken);

        RenderedPdfResolution renderedPdf = renderedPdfService.openRenderedPdf(metadata);

        LOG.info(OperationLog.format(
                "rendered_pdf_by_token",
                "success",
                "file_id", fileId,
                "tenant_id", metadata.tenantId(),
                "request_id", null,
                "storage_provider", metadata.storageProvider()
        ));

        return renderedPdf;
    }

    public void ensureRenderedPdfReady(String fileId, String accessToken) {
        FileMetadata metadata = requireAccessibleFile(fileId, accessToken);
        renderedPdfService.ensureRenderedPdfReady(metadata);

        LOG.info(OperationLog.format(
                "rendered_pdf_ready_by_token",
                "success",
                "file_id", fileId,
                "tenant_id", metadata.tenantId(),
                "request_id", null,
                "storage_provider", metadata.storageProvider()
        ));
    }

    public FileViewResponse getView(String fileId, String accessToken) {
        if (!downloadTokenVerifier.isEnabled()) {
            throw new NotFoundException("resource not found");
        }
        if (!ulidGenerator.isValid(fileId)) {
            throw new ValidationException("invalid fileId");
        }

        DownloadTokenClaims claims = downloadTokenVerifier.verify(accessToken);
        FileMetadata metadata = requireAccessibleFile(fileId, claims);
        requireViewPurpose(claims);
        FileViewResponse descriptor = viewDescriptorService.describePublic(metadata, claims);

        LOG.info(OperationLog.format(
                "view_descriptor_by_token",
                "success",
                "file_id", fileId,
                "tenant_id", metadata.tenantId(),
                "request_id", null,
                "storage_provider", metadata.storageProvider(),
                "viewer_type", descriptor.viewerType().value()
        ));
        return descriptor;
    }

    public DownloadFile openViewContent(String fileId, String accessToken) {
        FileMetadata metadata = requireAccessibleFile(fileId, accessToken);
        ViewerType viewerType = viewDescriptorService.resolveViewerType(metadata.mimeType());
        if (viewerType == ViewerType.PDF) {
            RenderedPdfResolution renderedPdf = renderedPdfService.openRenderedPdf(metadata);
            LOG.info(OperationLog.format(
                    "view_content_by_token",
                    "success",
                    "file_id", fileId,
                    "tenant_id", metadata.tenantId(),
                    "request_id", null,
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
                    "view_content_by_token",
                    "success",
                    "file_id", fileId,
                    "tenant_id", metadata.tenantId(),
                    "request_id", null,
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
                    "view_content_by_token",
                    "success",
                    "file_id", fileId,
                    "tenant_id", metadata.tenantId(),
                    "request_id", null,
                    "storage_provider", metadata.storageProvider(),
                    "viewer_type", viewerType.value()
            ));
            return textViewService.openTextContent(metadata);
        }
        throw new NotFoundException("view content is not available for current file");
    }

    private FileMetadata requireAccessibleFile(String fileId, String accessToken) {
        if (!downloadTokenVerifier.isEnabled()) {
            throw new NotFoundException("resource not found");
        }
        if (!ulidGenerator.isValid(fileId)) {
            throw new ValidationException("invalid fileId");
        }

        DownloadTokenClaims claims = downloadTokenVerifier.verify(accessToken);
        return requireAccessibleFile(fileId, claims);
    }

    private void requireViewPurpose(DownloadTokenClaims claims) {
        if (DownloadTokenSigner.VIEWER_PURPOSE.equals(claims.purpose())) {
            throw new ForbiddenException("download token purpose is not valid for view");
        }
    }

    private FileMetadata requireAccessibleFile(String fileId, DownloadTokenClaims claims) {
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
