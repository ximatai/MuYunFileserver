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
import net.ximatai.muyun.fileserver.domain.preview.FilePreviewArtifact;
import net.ximatai.muyun.fileserver.domain.preview.PreviewArtifactStatus;
import net.ximatai.muyun.fileserver.domain.preview.PreviewFailureCode;
import net.ximatai.muyun.fileserver.domain.preview.PreviewSourceKind;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FilePreviewArtifactRepository;
import net.ximatai.muyun.fileserver.infrastructure.storage.StorageProvider;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PreviewService {

    private static final String PDF_MIME_TYPE = "application/pdf";
    private static final String DEFAULT_ARTIFACT_KEY = "preview_pdf";

    @Inject
    FileServiceConfig config;

    @Inject
    FilePreviewArtifactRepository previewArtifactRepository;

    @Inject
    StorageProvider storageProvider;

    @Inject
    OfficePreviewConverter officePreviewConverter;

    @Inject
    PreviewPathResolver previewPathResolver;

    @Inject
    SupportedFileTypes supportedFileTypes;

    private final ConcurrentHashMap<String, Object> previewLocks = new ConcurrentHashMap<>();

    public void ensurePreviewReady(FileMetadata metadata) {
        if (!config.preview().enabled()) {
            throw new NotFoundException("resource not found");
        }
        ensurePreviewable(metadata);

        Optional<FilePreviewArtifact> existingArtifact = previewArtifactRepository.findByFileIdAndArtifactKey(metadata.id(), DEFAULT_ARTIFACT_KEY);
        if (existingArtifact.isPresent()) {
            FilePreviewArtifact artifact = existingArtifact.get();
            if (artifact.status() == PreviewArtifactStatus.READY && isArtifactAvailable(metadata, artifact)) {
                previewArtifactRepository.touchAccessedAt(metadata.id(), DEFAULT_ARTIFACT_KEY, Instant.now());
                return;
            }
            if (artifact.status() == PreviewArtifactStatus.FAILED && !shouldRetry(artifact)) {
                throw failureToException(artifact);
            }
        }

        Object lock = previewLocks.computeIfAbsent(metadata.id(), ignored -> new Object());
        synchronized (lock) {
            try {
                Optional<FilePreviewArtifact> reloadedArtifact = previewArtifactRepository.findByFileIdAndArtifactKey(metadata.id(), DEFAULT_ARTIFACT_KEY);
                if (reloadedArtifact.isPresent()) {
                    FilePreviewArtifact artifact = reloadedArtifact.get();
                    if (artifact.status() == PreviewArtifactStatus.READY && isArtifactAvailable(metadata, artifact)) {
                        previewArtifactRepository.touchAccessedAt(metadata.id(), DEFAULT_ARTIFACT_KEY, Instant.now());
                        return;
                    }
                    if (artifact.status() == PreviewArtifactStatus.FAILED && !shouldRetry(artifact)) {
                        throw failureToException(artifact);
                    }
                }
                generateOrRegister(metadata);
            } finally {
                previewLocks.remove(metadata.id(), lock);
            }
        }
    }

    public PreviewResolution openPreview(FileMetadata metadata) {
        ensurePreviewReady(metadata);
        FilePreviewArtifact artifact = previewArtifactRepository.findByFileIdAndArtifactKey(metadata.id(), DEFAULT_ARTIFACT_KEY)
                .orElseThrow(() -> new NotFoundException("preview not found"));
        previewArtifactRepository.touchAccessedAt(metadata.id(), DEFAULT_ARTIFACT_KEY, Instant.now());

        String filename = previewPathResolver.previewFilename(metadata);
        InputStream inputStream = switch (artifact.sourceKind()) {
            case ORIGINAL_PDF -> storageProvider.open(metadata.storageKey());
            case GENERATED_PDF -> {
                if (artifact.storageKey() == null || !storageProvider.exists(artifact.storageKey())) {
                    throw new NotFoundException("preview not found");
                }
                yield storageProvider.open(artifact.storageKey());
            }
        };

        long sizeBytes = artifact.sourceKind() == PreviewSourceKind.ORIGINAL_PDF
                ? metadata.sizeBytes()
                : artifact.sizeBytes() == null ? 0L : artifact.sizeBytes();
        return new PreviewResolution(filename, PDF_MIME_TYPE, sizeBytes, inputStream);
    }

    public void deletePreviewIfExists(FileMetadata metadata) {
        if (!config.preview().cache().cleanupOrphanPreviewOnFileDelete()) {
            return;
        }
        previewArtifactRepository.findByFileIdAndArtifactKey(metadata.id(), DEFAULT_ARTIFACT_KEY).ifPresent(artifact -> {
            if (artifact.sourceKind() == PreviewSourceKind.GENERATED_PDF
                    && artifact.storageKey() != null
                    && storageProvider.exists(artifact.storageKey())) {
                storageProvider.deleteIfExists(artifact.storageKey());
            }
            previewArtifactRepository.deleteByFileId(metadata.id());
        });
    }

    private void ensurePreviewable(FileMetadata metadata) {
        if (!isPreviewableMimeType(metadata.mimeType())) {
            throw new UnsupportedMediaTypeException("preview is not supported for current file type");
        }
        if (PDF_MIME_TYPE.equalsIgnoreCase(metadata.mimeType())) {
            return;
        }
        if (!config.preview().officeEnabled()) {
            throw new UnsupportedMediaTypeException("preview is not supported for current file type");
        }
    }

    private boolean isPreviewableMimeType(String mimeType) {
        return supportedFileTypes.isPreviewableMimeType(mimeType);
    }

    private boolean shouldRetry(FilePreviewArtifact artifact) {
        Instant retryAfter = artifact.generatedAt().plus(config.preview().libreoffice().retryFailureAfter());
        return Instant.now().isAfter(retryAfter);
    }

    private RuntimeException failureToException(FilePreviewArtifact artifact) {
        return switch (artifact.failureCode()) {
            case CONVERTER_UNAVAILABLE -> new ServiceUnavailableException("preview converter is not available");
            case CONVERSION_TIMEOUT -> new GatewayTimeoutException("preview conversion timed out");
            case CONVERSION_FAILED, INVALID_OUTPUT ->
                    new UnprocessableEntityException(artifact.failureMessage() == null
                            ? "preview conversion failed"
                            : artifact.failureMessage());
            case null -> new UnprocessableEntityException(artifact.failureMessage() == null
                    ? "preview conversion failed"
                    : artifact.failureMessage());
        };
    }

    private boolean isArtifactAvailable(FileMetadata metadata, FilePreviewArtifact artifact) {
        return switch (artifact.sourceKind()) {
            case ORIGINAL_PDF -> storageProvider.exists(metadata.storageKey());
            case GENERATED_PDF -> artifact.storageKey() != null && storageProvider.exists(artifact.storageKey());
        };
    }

    private void generateOrRegister(FileMetadata metadata) {
        if (PDF_MIME_TYPE.equalsIgnoreCase(metadata.mimeType())) {
            Instant now = Instant.now();
            previewArtifactRepository.save(new FilePreviewArtifact(
                    metadata.id(),
                    DEFAULT_ARTIFACT_KEY,
                    metadata.tenantId(),
                    PreviewSourceKind.ORIGINAL_PDF,
                    PreviewArtifactStatus.READY,
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
            PreviewConversionResult result = officePreviewConverter.convert(sourceTemp, metadata.originalFilename());
            pdfTemp = result.outputFile();
            String storageKey = previewPathResolver.storageKey(metadata);
            storageProvider.moveToPermanent(pdfTemp, storageKey);
            Instant now = Instant.now();
            previewArtifactRepository.save(new FilePreviewArtifact(
                    metadata.id(),
                    DEFAULT_ARTIFACT_KEY,
                    metadata.tenantId(),
                    PreviewSourceKind.GENERATED_PDF,
                    PreviewArtifactStatus.READY,
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
            saveFailure(metadata, PreviewFailureCode.CONVERTER_UNAVAILABLE, "preview converter is not available");
            throw exception;
        } catch (GatewayTimeoutException exception) {
            saveFailure(metadata, PreviewFailureCode.CONVERSION_TIMEOUT, "preview conversion timed out");
            throw exception;
        } catch (UnprocessableEntityException exception) {
            saveFailure(metadata, PreviewFailureCode.CONVERSION_FAILED, exception.getMessage());
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
            throw new net.ximatai.muyun.fileserver.common.exception.StorageException("failed to stage preview source file", exception);
        }
    }

    private void saveFailure(FileMetadata metadata, PreviewFailureCode code, String message) {
        Instant now = Instant.now();
        previewArtifactRepository.save(new FilePreviewArtifact(
                metadata.id(),
                DEFAULT_ARTIFACT_KEY,
                metadata.tenantId(),
                PreviewSourceKind.GENERATED_PDF,
                PreviewArtifactStatus.FAILED,
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
