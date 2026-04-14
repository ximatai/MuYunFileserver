package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import net.ximatai.muyun.fileserver.common.context.RequestContext;
import net.ximatai.muyun.fileserver.common.exception.ConflictException;
import net.ximatai.muyun.fileserver.common.exception.PayloadTooLargeException;
import net.ximatai.muyun.fileserver.common.exception.StorageException;
import net.ximatai.muyun.fileserver.common.exception.UnsupportedMediaTypeException;
import net.ximatai.muyun.fileserver.common.exception.ValidationException;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FileMetadataRepository;
import net.ximatai.muyun.fileserver.infrastructure.storage.StorageKeyFactory;
import net.ximatai.muyun.fileserver.infrastructure.storage.StorageProvider;
import net.ximatai.muyun.fileserver.infrastructure.ulid.UlidGenerator;
import org.apache.tika.Tika;
import org.jboss.resteasy.reactive.server.multipart.FileItem;
import org.jboss.resteasy.reactive.server.multipart.FormValue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

@ApplicationScoped
class UploadFilePreparer {

    private final Tika tika = new Tika();

    @Inject
    FileServiceConfig config;

    @Inject
    FileMetadataRepository repository;

    @Inject
    StorageProvider storageProvider;

    @Inject
    StorageKeyFactory storageKeyFactory;

    @Inject
    UlidGenerator ulidGenerator;

    @Inject
    SupportedFileTypes supportedFileTypes;

    List<PreparedUpload> prepare(UploadRequest uploadRequest, RequestContext requestContext) {
        return java.util.stream.IntStream.range(0, uploadRequest.fileValues().size())
                .mapToObj(index -> prepareSingle(
                        extractUploadFile(uploadRequest.fileValues().get(index)),
                        requestedIdAt(uploadRequest.requestedFileIds(), index),
                        uploadRequest.temporary(),
                        requestContext
                ))
                .toList();
    }

    private PreparedUpload prepareSingle(
            UploadFile uploadFile,
            String requestedFileId,
            boolean temporary,
            RequestContext requestContext
    ) {
        validateReportedSize(uploadFile.fileItem());

        String fileId = resolveFileId(requestedFileId);
        Path tempFile = storageProvider.createTempFile();
        FileDigest fileDigest = streamToTemp(uploadFile.fileItem(), tempFile);
        validateStreamedSize(fileDigest.sizeBytes());

        String originalFilename = Objects.requireNonNullElse(uploadFile.originalFilename(), fileId);
        String mimeType = supportedFileTypes.canonicalize(detectMimeType(tempFile, originalFilename, uploadFile.formValue()));
        validateMimeType(mimeType);

        return new PreparedUpload(
                fileId,
                requestContext.tenantId(),
                originalFilename,
                extensionOf(originalFilename),
                mimeType,
                fileDigest.sizeBytes(),
                fileDigest.sha256(),
                storageProvider.storageBucket(),
                storageKeyFactory.build(requestContext.tenantId(), fileId),
                Instant.now(),
                temporary,
                tempFile
        );
    }

    private UploadFile extractUploadFile(FormValue formValue) {
        if (!formValue.isFileItem()) {
            throw new ValidationException("files part must be file content");
        }
        return new UploadFile(formValue, formValue.getFileItem(), formValue.getFileName());
    }

    private void validateReportedSize(FileItem fileItem) {
        try {
            if (fileItem.getFileSize() > config.upload().maxFileSizeBytes()) {
                throw new PayloadTooLargeException("file exceeds max size limit");
            }
        } catch (IOException exception) {
            throw new StorageException("failed to inspect file size", exception);
        }
    }

    private String resolveFileId(String requestedFileId) {
        String fileId = requestedFileId != null ? requestedFileId : ulidGenerator.nextUlid();
        if (!ulidGenerator.isValid(fileId)) {
            throw new ValidationException("invalid file_id");
        }
        if (repository.existsById(fileId)) {
            throw new ConflictException("file_id already exists");
        }
        return fileId;
    }

    private FileDigest streamToTemp(FileItem fileItem, Path tempFile) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size;
            try (InputStream inputStream = new DigestInputStream(fileItem.getInputStream(), digest);
                 OutputStream outputStream = Files.newOutputStream(tempFile)) {
                size = inputStream.transferTo(outputStream);
            }
            return new FileDigest(HexFormat.of().formatHex(digest.digest()), size);
        } catch (IOException exception) {
            throw new StorageException("failed to persist temp upload", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("sha-256 algorithm is unavailable", exception);
        }
    }

    private void validateStreamedSize(long sizeBytes) {
        if (sizeBytes > config.upload().maxFileSizeBytes()) {
            throw new PayloadTooLargeException("file exceeds max size limit");
        }
    }

    private String detectMimeType(Path tempFile, String originalFilename, FormValue formValue) {
        try (InputStream inputStream = Files.newInputStream(tempFile)) {
            String detected = tika.detect(inputStream, originalFilename);
            if (detected != null && !MediaType.APPLICATION_OCTET_STREAM.equals(detected)) {
                return detected;
            }
        } catch (IOException exception) {
            throw new StorageException("failed to detect mime type", exception);
        }
        String headerContentType = formValue.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        return headerContentType == null || headerContentType.isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM
                : headerContentType;
    }

    private void validateMimeType(String mimeType) {
        if (!supportedFileTypes.isAllowedUploadMimeType(mimeType)) {
            throw new UnsupportedMediaTypeException("unsupported media type");
        }
    }

    private String extensionOf(String originalFilename) {
        int index = originalFilename.lastIndexOf('.');
        if (index < 0 || index == originalFilename.length() - 1) {
            return null;
        }
        return originalFilename.substring(index + 1).toLowerCase();
    }

    private String requestedIdAt(List<String> requestedFileIds, int index) {
        return index < requestedFileIds.size() ? requestedFileIds.get(index) : null;
    }

    private record UploadFile(
            FormValue formValue,
            FileItem fileItem,
            String originalFilename
    ) {
    }

    private record FileDigest(String sha256, long sizeBytes) {
    }
}
