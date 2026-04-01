package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import net.ximatai.muyun.fileserver.api.dto.UploadFileItemResponse;
import net.ximatai.muyun.fileserver.api.dto.UploadFilesResponse;
import net.ximatai.muyun.fileserver.common.context.RequestContext;
import net.ximatai.muyun.fileserver.common.context.RequestContextHolder;
import net.ximatai.muyun.fileserver.common.exception.ConflictException;
import net.ximatai.muyun.fileserver.common.exception.PayloadTooLargeException;
import net.ximatai.muyun.fileserver.common.exception.StorageException;
import net.ximatai.muyun.fileserver.common.exception.StorageCapacityException;
import net.ximatai.muyun.fileserver.common.exception.UnsupportedMediaTypeException;
import net.ximatai.muyun.fileserver.common.exception.ValidationException;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;
import net.ximatai.muyun.fileserver.domain.file.FileMetadata;
import net.ximatai.muyun.fileserver.domain.file.FileStatus;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FileMetadataRepository;
import net.ximatai.muyun.fileserver.infrastructure.storage.StorageKeyFactory;
import net.ximatai.muyun.fileserver.infrastructure.storage.StorageProvider;
import net.ximatai.muyun.fileserver.infrastructure.ulid.UlidGenerator;
import org.apache.tika.Tika;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.multipart.FileItem;
import org.jboss.resteasy.reactive.server.multipart.FormValue;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@ApplicationScoped
public class UploadService {

    private static final Logger LOG = Logger.getLogger(UploadService.class);

    private final Tika tika = new Tika();

    @Inject
    RequestContextHolder requestContextHolder;

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

    public UploadFilesResponse upload(MultipartFormDataInput input) {
        RequestContext requestContext = requestContextHolder.getRequired();
        Map<String, Collection<FormValue>> values = input.getValues();
        List<FormValue> fileValues = formValues(values, "files");
        List<String> requestedFileIds = textValues(values, "file_ids");
        String remark = singleOptionalText(values, "remark");

        validateRequest(fileValues, requestedFileIds);

        List<PreparedUpload> preparedUploads = new ArrayList<>();
        List<FileMetadata> insertedMetadata = new ArrayList<>();
        List<String> movedStorageKeys = new ArrayList<>();

        try {
            for (int index = 0; index < fileValues.size(); index++) {
                preparedUploads.add(prepareSingle(fileValues.get(index), requestedIdAt(requestedFileIds, index), requestContext));
            }

            List<UploadFileItemResponse> responseItems = new ArrayList<>();
            for (PreparedUpload preparedUpload : preparedUploads) {
                storageProvider.moveToPermanent(preparedUpload.tempFile(), preparedUpload.storageKey());
                movedStorageKeys.add(preparedUpload.storageKey());

                FileMetadata metadata = preparedUpload.toMetadata(remark, requestContext.userId(), storageProvider.providerName());
                repository.insert(metadata);
                insertedMetadata.add(metadata);

                responseItems.add(new UploadFileItemResponse(
                        metadata.id(),
                        metadata.originalFilename(),
                        metadata.mimeType(),
                        metadata.sizeBytes(),
                        metadata.uploadedAt()
                ));
            }

            LOG.infof("upload success fileCount=%d tenantId=%s userId=%s requestId=%s",
                    responseItems.size(), requestContext.tenantId(), requestContext.userId(), requestContext.requestId());
            return new UploadFilesResponse(List.copyOf(responseItems));
        } catch (RuntimeException exception) {
            rollback(insertedMetadata, movedStorageKeys, preparedUploads);
            LOG.warnf("upload failed tenantId=%s userId=%s requestId=%s reason=%s",
                    requestContext.tenantId(), requestContext.userId(), requestContext.requestId(), exception.getMessage());
            throw exception;
        } finally {
            preparedUploads.forEach(item -> storageProvider.deleteTempFile(item.tempFile()));
        }
    }

    private PreparedUpload prepareSingle(FormValue formValue, String requestedFileId, RequestContext requestContext) {
        if (!formValue.isFileItem()) {
            throw new ValidationException("files part must be file content");
        }

        FileItem fileItem = formValue.getFileItem();
        try {
            long reportedSize = fileItem.getFileSize();
            if (reportedSize > config.upload().maxFileSizeBytes()) {
                throw new PayloadTooLargeException("file exceeds max size limit");
            }
        } catch (IOException exception) {
            throw new StorageException("failed to inspect file size", exception);
        }

        String fileId = requestedFileId != null ? requestedFileId : ulidGenerator.nextUlid();
        if (!ulidGenerator.isValid(fileId)) {
            throw new ValidationException("invalid file_id");
        }
        if (repository.existsById(fileId)) {
            throw new ConflictException("file_id already exists");
        }

        Path tempFile = storageProvider.createTempFile();
        HashAndSize hashAndSize = streamToTemp(fileItem, tempFile);
        if (hashAndSize.sizeBytes() > config.upload().maxFileSizeBytes()) {
            throw new PayloadTooLargeException("file exceeds max size limit");
        }

        String originalFilename = Objects.requireNonNullElse(formValue.getFileName(), fileId);
        String mimeType = detectMimeType(tempFile, originalFilename, formValue);
        validateMimeType(mimeType);

        return new PreparedUpload(
                fileId,
                requestContext.tenantId(),
                originalFilename,
                extensionOf(originalFilename),
                mimeType,
                hashAndSize.sizeBytes(),
                hashAndSize.sha256(),
                storageKeyFactory.build(requestContext.tenantId(), fileId),
                Instant.now(),
                tempFile
        );
    }

    private HashAndSize streamToTemp(FileItem fileItem, Path tempFile) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size;
            try (InputStream inputStream = new DigestInputStream(fileItem.getInputStream(), digest);
                 OutputStream outputStream = Files.newOutputStream(tempFile)) {
                size = inputStream.transferTo(outputStream);
            }
            return new HashAndSize(HexFormat.of().formatHex(digest.digest()), size);
        } catch (IOException exception) {
            throw new StorageException("failed to persist temp upload", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("sha-256 algorithm is unavailable", exception);
        }
    }

    private void rollback(List<FileMetadata> insertedMetadata, List<String> movedStorageKeys, List<PreparedUpload> preparedUploads) {
        for (int index = insertedMetadata.size() - 1; index >= 0; index--) {
            try {
                repository.deleteById(insertedMetadata.get(index).id());
            } catch (RuntimeException exception) {
                LOG.errorf(exception, "failed to rollback metadata id=%s", insertedMetadata.get(index).id());
            }
        }

        for (int index = movedStorageKeys.size() - 1; index >= 0; index--) {
            try {
                storageProvider.deleteIfExists(movedStorageKeys.get(index));
            } catch (RuntimeException exception) {
                LOG.errorf(exception, "failed to rollback storage key=%s", movedStorageKeys.get(index));
            }
        }

        for (PreparedUpload preparedUpload : preparedUploads) {
            try {
                storageProvider.deleteTempFile(preparedUpload.tempFile());
            } catch (RuntimeException exception) {
                LOG.errorf(exception, "failed to cleanup temp upload %s", preparedUpload.tempFile());
            }
        }
    }

    private void validateRequest(List<FormValue> fileValues, List<String> requestedFileIds) {
        if (fileValues.isEmpty()) {
            throw new ValidationException("files is required");
        }
        if (fileValues.size() > config.upload().maxFileCount()) {
            throw new ValidationException("too many files in one request");
        }
        if (requestedFileIds.size() > fileValues.size()) {
            throw new ValidationException("file_ids count cannot exceed files count");
        }
        if (config.storage().rootDir().toFile().getUsableSpace() < config.upload().minFreeSpaceBytes()) {
            throw new StorageCapacityException("insufficient storage space");
        }
    }

    private List<FormValue> formValues(Map<String, Collection<FormValue>> values, String key) {
        Collection<FormValue> items = values.get(key);
        if (items == null) {
            return List.of();
        }
        return new ArrayList<>(items);
    }

    private List<String> textValues(Map<String, Collection<FormValue>> values, String key) {
        Collection<FormValue> items = values.get(key);
        if (items == null) {
            return List.of();
        }
        List<String> results = new ArrayList<>();
        for (FormValue item : items) {
            if (item.isFileItem()) {
                throw new ValidationException(key + " must be text field");
            }
            String value = item.getValue();
            if (value == null || value.isBlank()) {
                throw new ValidationException(key + " contains blank value");
            }
            results.add(value.trim());
        }
        return results;
    }

    private String singleOptionalText(Map<String, Collection<FormValue>> values, String key) {
        List<String> items = textValues(values, key);
        if (items.isEmpty()) {
            return null;
        }
        if (items.size() > 1) {
            throw new ValidationException(key + " must appear at most once");
        }
        return items.getFirst();
    }

    private String requestedIdAt(List<String> requestedFileIds, int index) {
        return index < requestedFileIds.size() ? requestedFileIds.get(index) : null;
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
        if (!config.security().allowedMimeTypes().contains(mimeType)) {
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

    private record HashAndSize(String sha256, long sizeBytes) {
    }

    private record PreparedUpload(
            String fileId,
            String tenantId,
            String originalFilename,
            String extension,
            String mimeType,
            long sizeBytes,
            String sha256,
            String storageKey,
            Instant uploadedAt,
            Path tempFile
    ) {
        FileMetadata toMetadata(String remark, String uploadedBy, String storageProvider) {
            return new FileMetadata(
                    fileId,
                    tenantId,
                    originalFilename,
                    extension,
                    mimeType,
                    sizeBytes,
                    sha256,
                    storageProvider,
                    null,
                    storageKey,
                    FileStatus.ACTIVE,
                    uploadedBy,
                    uploadedAt,
                    null,
                    null,
                    remark
            );
        }
    }
}
