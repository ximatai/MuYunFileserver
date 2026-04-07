package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.api.dto.UploadFileItemResponse;
import net.ximatai.muyun.fileserver.api.dto.UploadFilesResponse;
import net.ximatai.muyun.fileserver.common.context.RequestContext;
import net.ximatai.muyun.fileserver.common.context.RequestContextHolder;
import net.ximatai.muyun.fileserver.common.log.OperationLog;
import net.ximatai.muyun.fileserver.domain.file.FileMetadata;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FileMetadataRepository;
import net.ximatai.muyun.fileserver.infrastructure.storage.StorageProvider;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class UploadService {

    private static final Logger LOG = Logger.getLogger(UploadService.class);

    @Inject
    RequestContextHolder requestContextHolder;

    @Inject
    UploadRequestParser uploadRequestParser;

    @Inject
    FileMetadataRepository repository;

    @Inject
    StorageProvider storageProvider;

    @Inject
    UploadFilePreparer uploadFilePreparer;

    public UploadFilesResponse upload(MultipartFormDataInput input) {
        RequestContext requestContext = requestContextHolder.getRequired();
        return upload(input, requestContext, true);
    }

    public UploadFilesResponse upload(MultipartFormDataInput input, RequestContext requestContext, boolean allowRequestedFileIds) {
        UploadRequest uploadRequest = uploadRequestParser.parse(input, allowRequestedFileIds);

        List<PreparedUpload> preparedUploads = new ArrayList<>();
        List<FileMetadata> insertedMetadata = new ArrayList<>();
        List<String> movedStorageKeys = new ArrayList<>();

        try {
            preparedUploads.addAll(uploadFilePreparer.prepare(uploadRequest, requestContext));

            List<UploadFileItemResponse> responseItems = persistUploads(
                    preparedUploads,
                    uploadRequest.remark(),
                    requestContext,
                    insertedMetadata,
                    movedStorageKeys
            );

            LOG.info(OperationLog.format(
                    "upload",
                    "success",
                    "tenant_id", requestContext.tenantId(),
                    "user_id", requestContext.userId(),
                    "request_id", requestContext.requestId(),
                    "storage_provider", storageProvider.providerName(),
                    "file_count", String.valueOf(responseItems.size())
            ));
            return new UploadFilesResponse(List.copyOf(responseItems));
        } catch (RuntimeException exception) {
            rollback(insertedMetadata, movedStorageKeys, preparedUploads);
            LOG.warn(OperationLog.format(
                    "upload",
                    "failure",
                    "tenant_id", requestContext.tenantId(),
                    "user_id", requestContext.userId(),
                    "request_id", requestContext.requestId(),
                    "storage_provider", storageProvider.providerName(),
                    "reason", exception.getMessage()
            ));
            throw exception;
        } finally {
            preparedUploads.forEach(item -> storageProvider.deleteTempFile(item.tempFile()));
        }
    }

    private List<UploadFileItemResponse> persistUploads(
            List<PreparedUpload> preparedUploads,
            String remark,
            RequestContext requestContext,
            List<FileMetadata> insertedMetadata,
            List<String> movedStorageKeys
    ) {
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
        return responseItems;
    }

    private void rollback(List<FileMetadata> insertedMetadata, List<String> movedStorageKeys, List<PreparedUpload> preparedUploads) {
        for (int index = insertedMetadata.size() - 1; index >= 0; index--) {
            try {
                repository.deleteById(insertedMetadata.get(index).id());
            } catch (RuntimeException exception) {
                LOG.error(OperationLog.format(
                        "upload_rollback_metadata",
                        "failure",
                        "file_id", insertedMetadata.get(index).id(),
                        "reason", exception.getMessage()
                ), exception);
            }
        }

        for (int index = movedStorageKeys.size() - 1; index >= 0; index--) {
            try {
                storageProvider.deleteIfExists(movedStorageKeys.get(index));
            } catch (RuntimeException exception) {
                LOG.error(OperationLog.format(
                        "upload_rollback_storage",
                        "failure",
                        "storage_key", movedStorageKeys.get(index),
                        "storage_provider", storageProvider.providerName(),
                        "reason", exception.getMessage()
                ), exception);
            }
        }

        for (PreparedUpload preparedUpload : preparedUploads) {
            try {
                storageProvider.deleteTempFile(preparedUpload.tempFile());
            } catch (RuntimeException exception) {
                LOG.error(OperationLog.format(
                        "temp_cleanup",
                        "failure",
                        "file_id", preparedUpload.fileId(),
                        "storage_provider", storageProvider.providerName(),
                        "reason", exception.getMessage()
                ), exception);
            }
        }
    }
}
