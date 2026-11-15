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
import net.ximatai.muyun.fileserver.application.storage.AssetStorage;
import net.ximatai.muyun.fileserver.application.storage.LocalWorkspace;
import net.ximatai.muyun.fileserver.domain.file.StoredObject;
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
    AssetStorage assetStorage;

    @Inject
    LocalWorkspace workspace;

    @Inject
    UploadFilePreparer uploadFilePreparer;

    public UploadFilesResponse upload(MultipartFormDataInput input) {
        RequestContext requestContext = requestContextHolder.getRequired();
        return upload(input, requestContext, true);
    }

    public UploadFilesResponse upload(MultipartFormDataInput input, RequestContext requestContext, boolean allowRequestedFileIds) {
        return upload(uploadRequestParser.parse(input, allowRequestedFileIds), requestContext);
    }

    /**
     * Token-authorised browser uploads are staging objects.  The caller cannot
     * make them permanent by supplying a multipart {@code temporary=false}.
     */
    public UploadFilesResponse uploadTemporary(MultipartFormDataInput input, RequestContext requestContext) {
        UploadRequest request = uploadRequestParser.parseTokenUpload(input);
        return upload(new UploadRequest(request.fileValues(), request.requestedFileIds(), request.remark(), true),
                requestContext);
    }

    private UploadFilesResponse upload(UploadRequest uploadRequest, RequestContext requestContext) {

        List<PreparedUpload> preparedUploads = new ArrayList<>();
        List<FileMetadata> insertedMetadata = new ArrayList<>();
        List<StoredObject> writtenObjects = new ArrayList<>();

        try {
            preparedUploads.addAll(uploadFilePreparer.prepare(uploadRequest, requestContext));

            List<UploadFileItemResponse> responseItems = persistUploads(
                    preparedUploads,
                    uploadRequest.remark(),
                    requestContext,
                    insertedMetadata,
                    writtenObjects
            );

            LOG.info(OperationLog.format(
                    "upload",
                    "success",
                    "tenant_id", requestContext.tenantId(),
                    "user_id", requestContext.userId(),
                    "request_id", requestContext.requestId(),
                    "storage_provider", assetStorage.writeLocation().type(),
                    "file_count", String.valueOf(responseItems.size())
            ));
            return new UploadFilesResponse(List.copyOf(responseItems));
        } catch (RuntimeException exception) {
            rollback(insertedMetadata, writtenObjects);
            LOG.warn(OperationLog.format(
                    "upload",
                    "failure",
                    "tenant_id", requestContext.tenantId(),
                    "user_id", requestContext.userId(),
                    "request_id", requestContext.requestId(),
                    "storage_provider", assetStorage.writeLocation().type(),
                    "reason", exception.getMessage()
            ));
            throw exception;
        } finally {
            cleanupTemporaryFiles(preparedUploads);
        }
    }

    private List<UploadFileItemResponse> persistUploads(
            List<PreparedUpload> preparedUploads,
            String remark,
            RequestContext requestContext,
            List<FileMetadata> insertedMetadata,
            List<StoredObject> writtenObjects
    ) {
        List<UploadFileItemResponse> responseItems = new ArrayList<>();
        for (PreparedUpload preparedUpload : preparedUploads) {
            assetStorage.write(preparedUpload.tempFile(), preparedUpload.storedObject());
            writtenObjects.add(preparedUpload.storedObject());

            FileMetadata metadata = preparedUpload.toMetadata(remark, requestContext.userId());
            repository.insert(metadata);
            insertedMetadata.add(metadata);

            responseItems.add(new UploadFileItemResponse(
                    metadata.id(),
                    metadata.originalFilename(),
                    metadata.mimeType(),
                    metadata.sizeBytes(),
                    metadata.uploadedAt(),
                    metadata.temporary()
            ));
        }
        return responseItems;
    }

    private void rollback(List<FileMetadata> insertedMetadata, List<StoredObject> writtenObjects) {
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

        for (int index = writtenObjects.size() - 1; index >= 0; index--) {
            try {
                assetStorage.deleteIfExists(writtenObjects.get(index));
            } catch (RuntimeException exception) {
                LOG.error(OperationLog.format(
                        "upload_rollback_storage",
                        "failure",
                        "storage_key", writtenObjects.get(index).key(),
                        "storage_location", writtenObjects.get(index).locationId(),
                        "reason", exception.getMessage()
                ), exception);
            }
        }
    }

    private void cleanupTemporaryFiles(List<PreparedUpload> preparedUploads) {
        for (PreparedUpload preparedUpload : preparedUploads) {
            try {
                workspace.deleteTempFile(preparedUpload.tempFile());
            } catch (RuntimeException exception) {
                LOG.error(OperationLog.format(
                        "temp_cleanup",
                        "failure",
                        "file_id", preparedUpload.fileId(),
                        "storage_provider", assetStorage.writeLocation().type(),
                        "reason", exception.getMessage()
                ), exception);
            }
        }
    }
}
