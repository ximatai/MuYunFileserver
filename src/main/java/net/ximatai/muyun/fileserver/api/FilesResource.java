package net.ximatai.muyun.fileserver.api;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.ximatai.muyun.fileserver.api.dto.DeleteFileResult;
import net.ximatai.muyun.fileserver.api.dto.FileMetadataResponse;
import net.ximatai.muyun.fileserver.api.dto.FileViewResponse;
import net.ximatai.muyun.fileserver.api.dto.PromoteFilesRequest;
import net.ximatai.muyun.fileserver.api.dto.PromoteFilesResponse;
import net.ximatai.muyun.fileserver.api.dto.RenameFileRequest;
import net.ximatai.muyun.fileserver.api.dto.UploadFilesResponse;
import net.ximatai.muyun.fileserver.application.DownloadFile;
import net.ximatai.muyun.fileserver.application.FileCommandService;
import net.ximatai.muyun.fileserver.application.FileQueryService;
import net.ximatai.muyun.fileserver.application.PreviewResolution;
import net.ximatai.muyun.fileserver.application.UploadService;
import net.ximatai.muyun.fileserver.common.api.ApiResponses;
import net.ximatai.muyun.fileserver.common.api.DownloadResponses;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;

@Path("/api/v1/files")
@Produces(MediaType.APPLICATION_JSON)
@Blocking
public class FilesResource {

    @Inject
    UploadService uploadService;

    @Inject
    FileQueryService fileQueryService;

    @Inject
    FileCommandService fileCommandService;

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response upload(MultipartFormDataInput input) {
        UploadFilesResponse response = uploadService.upload(input);
        return Response.ok(ApiResponses.ok(response)).build();
    }

    @GET
    @Path("/{fileId}")
    public Response getMetadata(@RestPath String fileId) {
        FileMetadataResponse response = fileQueryService.getMetadata(fileId);
        return Response.ok(ApiResponses.ok(response)).build();
    }

    @GET
    @Path("/{fileId}/view")
    public Response getView(@RestPath String fileId) {
        FileViewResponse response = fileQueryService.getView(fileId);
        return Response.ok(ApiResponses.ok(response)).build();
    }

    @GET
    @Path("/{fileId}/view/content")
    @Produces(MediaType.WILDCARD)
    public Response viewContent(@RestPath String fileId) {
        return DownloadResponses.inline(fileQueryService.openViewContent(fileId));
    }

    @GET
    @Path("/{fileId}/download")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response download(@RestPath String fileId) {
        DownloadFile file = fileQueryService.openDownload(fileId);
        return DownloadResponses.ok(file);
    }

    @GET
    @Path("/{fileId}/preview")
    public Response preview(@RestPath String fileId) {
        fileQueryService.ensurePreviewReady(fileId);
        return Response.status(Response.Status.FOUND)
                .header("Location", "preview/content")
                .build();
    }

    @GET
    @Path("/{fileId}/preview/content")
    @Produces("application/pdf")
    public Response previewContent(@RestPath String fileId) {
        PreviewResolution preview = fileQueryService.openPreview(fileId);
        return DownloadResponses.inline(new DownloadFile(
                fileId,
                preview.originalFilename(),
                preview.mimeType(),
                preview.sizeBytes(),
                preview.inputStream()
        ));
    }

    @PUT
    @Path("/{fileId}/name")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response rename(@RestPath String fileId, RenameFileRequest request) {
        FileMetadataResponse response = fileCommandService.rename(
                fileId,
                request == null ? null : request.originalFilename()
        );
        return Response.ok(ApiResponses.ok(response)).build();
    }

    @POST
    @Path("/promote")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response promote(PromoteFilesRequest request) {
        PromoteFilesResponse response = fileCommandService.promote(request == null ? null : request.fileIds());
        return Response.ok(ApiResponses.ok(response)).build();
    }

    @DELETE
    @Path("/{fileId}")
    public Response delete(@RestPath String fileId) {
        DeleteFileResult response = fileCommandService.delete(fileId);
        return Response.ok(ApiResponses.ok(response)).build();
    }
}
