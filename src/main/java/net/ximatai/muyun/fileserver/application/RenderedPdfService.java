package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.common.exception.ForbiddenException;
import net.ximatai.muyun.fileserver.common.exception.GatewayTimeoutException;
import net.ximatai.muyun.fileserver.common.exception.NotFoundException;
import net.ximatai.muyun.fileserver.common.exception.ServiceUnavailableException;
import net.ximatai.muyun.fileserver.common.exception.UnprocessableEntityException;
import net.ximatai.muyun.fileserver.common.exception.UnsupportedMediaTypeException;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;
import net.ximatai.muyun.fileserver.domain.file.FileMetadata;
import net.ximatai.muyun.fileserver.domain.view.FileViewArtifact;
import net.ximatai.muyun.fileserver.domain.view.ViewArtifactFailureCode;
import net.ximatai.muyun.fileserver.domain.view.ViewArtifactSourceKind;
import net.ximatai.muyun.fileserver.domain.view.ViewArtifactStatus;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FileViewArtifactRepository;
import net.ximatai.muyun.fileserver.infrastructure.storage.StorageProvider;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class RenderedPdfService {

    private static final String PDF_MIME_TYPE = "application/pdf";
    private static final String DEFAULT_ARTIFACT_KEY = "view_pdf";

    @Inject
    FileServiceConfig config;

    @Inject
    FileViewArtifactRepository viewArtifactRepository;

    @Inject
    StorageProvider storageProvider;

    @Inject
    OfficePdfRenderer officePdfRenderer;

    @Inject
    RenderedPdfPathResolver renderedPdfPathResolver;

    @Inject
    SupportedFileTypes supportedFileTypes;

    private final ConcurrentHashMap<String, Object> renderedPdfLocks = new ConcurrentHashMap<>();

    public void ensureRenderedPdfReady(FileMetadata metadata) {
        if (!config.renderedPdf().enabled()) {
            throw new NotFoundException("resource not found");
        }
        ensurePdfRenderable(metadata);

        Optional<FileViewArtifact> existingArtifact = viewArtifactRepository.findByFileIdAndArtifactKey(metadata.id(), DEFAULT_ARTIFACT_KEY);
        if (existingArtifact.isPresent()) {
            FileViewArtifact artifact = existingArtifact.get();
            if (artifact.status() == ViewArtifactStatus.READY && isArtifactAvailable(metadata, artifact)) {
                viewArtifactRepository.touchAccessedAt(metadata.id(), DEFAULT_ARTIFACT_KEY, Instant.now());
                return;
            }
            if (artifact.status() == ViewArtifactStatus.FAILED && !shouldRetry(artifact)) {
                throw failureToException(artifact);
            }
        }

        Object lock = renderedPdfLocks.computeIfAbsent(metadata.id(), ignored -> new Object());
        synchronized (lock) {
            try {
                Optional<FileViewArtifact> reloadedArtifact = viewArtifactRepository.findByFileIdAndArtifactKey(metadata.id(), DEFAULT_ARTIFACT_KEY);
                if (reloadedArtifact.isPresent()) {
                    FileViewArtifact artifact = reloadedArtifact.get();
                    if (artifact.status() == ViewArtifactStatus.READY && isArtifactAvailable(metadata, artifact)) {
                        viewArtifactRepository.touchAccessedAt(metadata.id(), DEFAULT_ARTIFACT_KEY, Instant.now());
                        return;
                    }
                    if (artifact.status() == ViewArtifactStatus.FAILED && !shouldRetry(artifact)) {
                        throw failureToException(artifact);
                    }
                }
                generateOrRegister(metadata);
            } finally {
                renderedPdfLocks.remove(metadata.id(), lock);
            }
        }
    }

    public RenderedPdfResolution openRenderedPdf(FileMetadata metadata) {
        ensureRenderedPdfReady(metadata);
        FileViewArtifact artifact = viewArtifactRepository.findByFileIdAndArtifactKey(metadata.id(), DEFAULT_ARTIFACT_KEY)
                .orElseThrow(() -> new NotFoundException("rendered pdf not found"));
        viewArtifactRepository.touchAccessedAt(metadata.id(), DEFAULT_ARTIFACT_KEY, Instant.now());

        String filename = renderedPdfPathResolver.renderedPdfFilename(metadata);
        InputStreamSupplier inputStreamSupplier = switch (artifact.sourceKind()) {
            case ORIGINAL_PDF -> () -> storageProvider.open(metadata.storageKey());
            case GENERATED_PDF -> {
                if (artifact.storageKey() == null || !storageProvider.exists(artifact.storageKey())) {
                    throw new NotFoundException("rendered pdf not found");
                }
                yield () -> storageProvider.open(artifact.storageKey());
            }
        };
        RangeInputStreamSupplier rangeInputStreamSupplier = switch (artifact.sourceKind()) {
            case ORIGINAL_PDF -> (start, length) -> storageProvider.openRange(metadata.storageKey(), start, length);
            case GENERATED_PDF -> {
                if (artifact.storageKey() == null || !storageProvider.exists(artifact.storageKey())) {
                    throw new NotFoundException("rendered pdf not found");
                }
                yield (start, length) -> storageProvider.openRange(artifact.storageKey(), start, length);
            }
        };

        long sizeBytes = artifact.sourceKind() == ViewArtifactSourceKind.ORIGINAL_PDF
                ? metadata.sizeBytes()
                : artifact.sizeBytes() == null ? 0L : artifact.sizeBytes();
        return new RenderedPdfResolution(filename, PDF_MIME_TYPE, sizeBytes, inputStreamSupplier, rangeInputStreamSupplier);
    }

    public void deleteRenderedPdfIfExists(FileMetadata metadata) {
        if (!config.renderedPdf().artifactCache().cleanupOrphanViewArtifactOnFileDelete()) {
            return;
        }
        viewArtifactRepository.findByFileIdAndArtifactKey(metadata.id(), DEFAULT_ARTIFACT_KEY).ifPresent(artifact -> {
            if (artifact.sourceKind() == ViewArtifactSourceKind.GENERATED_PDF
                    && artifact.storageKey() != null
                    && storageProvider.exists(artifact.storageKey())) {
                storageProvider.deleteIfExists(artifact.storageKey());
            }
            viewArtifactRepository.deleteByFileId(metadata.id());
        });
    }

    private void ensurePdfRenderable(FileMetadata metadata) {
        if (!supportsRenderedPdfMimeType(metadata.mimeType())) {
            throw new UnsupportedMediaTypeException("rendered pdf is not supported for current file type");
        }
        if (PDF_MIME_TYPE.equalsIgnoreCase(metadata.mimeType())) {
            return;
        }
        if (!config.renderedPdf().officeRenderingEnabled()) {
            throw new UnsupportedMediaTypeException("rendered pdf is not supported for current file type");
        }
    }

    private boolean supportsRenderedPdfMimeType(String mimeType) {
        return supportedFileTypes.supportsRenderedPdfMimeType(mimeType);
    }

    private boolean shouldRetry(FileViewArtifact artifact) {
        Instant retryAfter = artifact.generatedAt().plus(config.renderedPdf().libreoffice().retryFailureAfter());
        return Instant.now().isAfter(retryAfter);
    }

    private RuntimeException failureToException(FileViewArtifact artifact) {
        return switch (artifact.failureCode()) {
            case CONVERTER_UNAVAILABLE -> new ServiceUnavailableException("pdf renderer is not available");
            case CONVERSION_TIMEOUT -> new GatewayTimeoutException("pdf rendering timed out");
            case CONVERSION_FAILED, INVALID_OUTPUT ->
                    new UnprocessableEntityException(artifact.failureMessage() == null
                            ? "pdf rendering failed"
                            : artifact.failureMessage());
            case null -> new UnprocessableEntityException(artifact.failureMessage() == null
                    ? "pdf rendering failed"
                    : artifact.failureMessage());
        };
    }

    private boolean isArtifactAvailable(FileMetadata metadata, FileViewArtifact artifact) {
        return switch (artifact.sourceKind()) {
            case ORIGINAL_PDF -> storageProvider.exists(metadata.storageKey());
            case GENERATED_PDF -> artifact.storageKey() != null && storageProvider.exists(artifact.storageKey());
        };
    }

    private void generateOrRegister(FileMetadata metadata) {
        if (PDF_MIME_TYPE.equalsIgnoreCase(metadata.mimeType())) {
            Instant now = Instant.now();
            viewArtifactRepository.save(new FileViewArtifact(
                    metadata.id(),
                    DEFAULT_ARTIFACT_KEY,
                    metadata.tenantId(),
                    ViewArtifactSourceKind.ORIGINAL_PDF,
                    ViewArtifactStatus.READY,
                    PDF_MIME_TYPE,
                    metadata.storageProvider(),
                    metadata.storageBucket(),
                    metadata.storageKey(),
                    metadata.sizeBytes(),
                    metadata.sha256(),
                    now,
                    now,
                    null,
                    null
            ));
            return;
        }

        Path sourceTemp = null;
        Path pdfTemp = null;
        try {
            sourceTemp = createSourceTempFile(metadata);
            RenderedPdfConversionResult result = officePdfRenderer.convert(sourceTemp, metadata.originalFilename());
            pdfTemp = result.outputFile();
            String storageKey = renderedPdfPathResolver.storageKey(metadata);
            storageProvider.moveToPermanent(pdfTemp, storageKey);
            Instant now = Instant.now();
            viewArtifactRepository.save(new FileViewArtifact(
                    metadata.id(),
                    DEFAULT_ARTIFACT_KEY,
                    metadata.tenantId(),
                    ViewArtifactSourceKind.GENERATED_PDF,
                    ViewArtifactStatus.READY,
                    PDF_MIME_TYPE,
                    storageProvider.providerName(),
                    storageProvider.storageBucket(),
                    storageKey,
                    result.sizeBytes(),
                    result.sha256(),
                    now,
                    now,
                    null,
                    null
            ));
        } catch (ServiceUnavailableException exception) {
            saveFailure(metadata, ViewArtifactFailureCode.CONVERTER_UNAVAILABLE, "pdf renderer is not available");
            throw exception;
        } catch (GatewayTimeoutException exception) {
            saveFailure(metadata, ViewArtifactFailureCode.CONVERSION_TIMEOUT, "pdf rendering timed out");
            throw exception;
        } catch (UnprocessableEntityException exception) {
            saveFailure(metadata, ViewArtifactFailureCode.CONVERSION_FAILED, exception.getMessage());
            throw exception;
        } finally {
            if (sourceTemp != null) {
                storageProvider.deleteTempFile(sourceTemp);
            }
            if (pdfTemp != null && Files.exists(pdfTemp)) {
                storageProvider.deleteTempFile(pdfTemp);
                Path parent = pdfTemp.getParent();
                if (parent != null && parent.startsWith(config.storage().tempDir())) {
                    deleteDirectory(parent);
                }
            }
        }
    }

    private Path createSourceTempFile(FileMetadata metadata) {
        Path tempFile = storageProvider.createTempFile();
        if (!storageProvider.exists(metadata.storageKey())) {
            storageProvider.deleteTempFile(tempFile);
            throw new NotFoundException("file not found");
        }
        try (InputStream inputStream = storageProvider.open(metadata.storageKey())) {
            Files.copy(inputStream, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return tempFile;
        } catch (net.ximatai.muyun.fileserver.common.exception.StorageException exception) {
            storageProvider.deleteTempFile(tempFile);
            throw exception;
        } catch (java.io.IOException exception) {
            storageProvider.deleteTempFile(tempFile);
            throw new net.ximatai.muyun.fileserver.common.exception.StorageException("failed to stage source file for pdf rendering", exception);
        }
    }

    private void saveFailure(FileMetadata metadata, ViewArtifactFailureCode code, String message) {
        Instant now = Instant.now();
        viewArtifactRepository.save(new FileViewArtifact(
                metadata.id(),
                DEFAULT_ARTIFACT_KEY,
                metadata.tenantId(),
                ViewArtifactSourceKind.GENERATED_PDF,
                ViewArtifactStatus.FAILED,
                PDF_MIME_TYPE,
                null,
                null,
                null,
                null,
                null,
                now,
                now,
                code,
                message
        ));
    }

    private void deleteDirectory(Path directory) {
        try (var walk = Files.walk(directory)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }
}
