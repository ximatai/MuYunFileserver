package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.api.dto.UploadFileItemResponse;
import net.ximatai.muyun.fileserver.api.dto.UploadFilesResponse;
import net.ximatai.muyun.fileserver.application.storage.AssetStorage;
import net.ximatai.muyun.fileserver.application.storage.LocalWorkspace;
import net.ximatai.muyun.fileserver.common.context.RequestContext;
import net.ximatai.muyun.fileserver.common.context.RequestContextHolder;
import net.ximatai.muyun.fileserver.common.exception.ConflictException;
import net.ximatai.muyun.fileserver.common.log.OperationLog;
import net.ximatai.muyun.fileserver.domain.file.FileMetadata;
import net.ximatai.muyun.fileserver.infrastructure.persistence.FileMetadataRepository;
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
        List<FileMetadata> writtenFiles = new ArrayList<>();
        boolean publicationStarted = false;

        try {
            preparedUploads.addAll(uploadFilePreparer.prepare(uploadRequest, requestContext));

            for (PreparedUpload prepared : preparedUploads) {
                FileMetadata metadata = prepared.toMetadata(uploadRequest.remark(), requestContext.userId());
                assetStorage.write(prepared.tempFile(), metadata.storedObject());
                writtenFiles.add(metadata);
            }
            publicationStarted = true;
            repository.insertAll(writtenFiles);
            var responseItems = writtenFiles.stream().map(UploadService::toResponseItem).toList();

            LOG.info(OperationLog.format(
                    "upload",
                    "success",
                    "tenant_id", requestContext.tenantId(),
                    "user_id", requestContext.userId(),
                    "request_id", requestContext.requestId(),
                    "storage_provider", assetStorage.writeLocation().type(),
                    "file_count", String.valueOf(responseItems.size())
            ));
            return new UploadFilesResponse(responseItems);
        } catch (RuntimeException exception) {
            rollback(writtenFiles, publicationStarted && !(exception instanceof ConflictException));
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

    private static UploadFileItemResponse toResponseItem(FileMetadata metadata) {
        return new UploadFileItemResponse(metadata.id(), metadata.originalFilename(), metadata.mimeType(),
                metadata.sizeBytes(), metadata.uploadedAt(), metadata.temporary());
    }

    private void rollback(List<FileMetadata> writtenFiles, boolean publicationUncertain) {
        for (FileMetadata metadata : writtenFiles.reversed()) {
            var object = metadata.storedObject();
            try {
                // A database error can be reported after commit. Preserve objects referenced by that commit.
                if (publicationUncertain && repository.findById(metadata.id())
                        .map(FileMetadata::storedObject).filter(object::equals).isPresent()) {
                    continue;
                }
                assetStorage.deleteIfExists(object);
            } catch (RuntimeException exception) {
                LOG.error(OperationLog.format(
                        "upload_rollback_storage",
                        "failure",
                        "storage_key", object.key(),
                        "storage_location", object.locationId(),
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
