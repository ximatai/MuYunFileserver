package net.ximatai.muyun.fileserver.api;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.ximatai.muyun.fileserver.application.DownloadFile;
import net.ximatai.muyun.fileserver.application.PreviewResolution;
import net.ximatai.muyun.fileserver.application.TokenFileCommandService;
import net.ximatai.muyun.fileserver.application.TokenFileQueryService;
import net.ximatai.muyun.fileserver.application.TokenUploadService;
import net.ximatai.muyun.fileserver.api.dto.DeleteFileResult;
import net.ximatai.muyun.fileserver.api.dto.FileMetadataResponse;
import net.ximatai.muyun.fileserver.api.dto.FileViewResponse;
import net.ximatai.muyun.fileserver.api.dto.UploadFilesResponse;
import net.ximatai.muyun.fileserver.common.api.ApiResponses;
import net.ximatai.muyun.fileserver.common.api.DownloadResponses;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;

@Path("/api/v1/public/files")
@Blocking
public class PublicFilesResource {

    @Inject
    TokenUploadService tokenUploadService;

    @Inject
    TokenFileQueryService tokenFileQueryService;

    @Inject
    TokenFileCommandService tokenFileCommandService;

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response upload(MultipartFormDataInput input, @QueryParam("access_token") String accessToken) {
        UploadFilesResponse response = tokenUploadService.upload(input, accessToken);
        return Response.ok(ApiResponses.ok(response)).build();
    }

    @GET
    @Path("/{fileId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getMetadata(@RestPath String fileId, @QueryParam("access_token") String accessToken) {
        FileMetadataResponse response = tokenFileQueryService.getMetadata(fileId, accessToken);
        return Response.ok(ApiResponses.ok(response)).build();
    }

    @GET
    @Path("/{fileId}/view")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getView(@RestPath String fileId, @QueryParam("access_token") String accessToken) {
        FileViewResponse response = tokenFileQueryService.getView(fileId, accessToken);
        return Response.ok(ApiResponses.ok(response)).build();
    }

    @GET
    @Path("/{fileId}/view/content/{accessToken}")
    @Produces("application/pdf")
    public Response viewContent(@RestPath String fileId, @RestPath String accessToken) {
        PreviewResolution preview = tokenFileQueryService.openPreview(fileId, accessToken);
        return DownloadResponses.inline(new DownloadFile(
                fileId,
                preview.originalFilename(),
                preview.mimeType(),
                preview.sizeBytes(),
                preview.inputStream()
        ));
    }

    @GET
    @Path("/{fileId}/download")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response download(@RestPath String fileId, @QueryParam("access_token") String accessToken) {
        DownloadFile file = tokenFileQueryService.openDownload(fileId, accessToken);
        return DownloadResponses.ok(file);
    }

    @GET
    @Path("/{fileId}/preview")
    public Response preview(@RestPath String fileId, @QueryParam("access_token") String accessToken) {
        tokenFileQueryService.ensurePreviewReady(fileId, accessToken);
        return Response.status(Response.Status.FOUND)
                .header("Location", "preview/content?access_token=" + java.net.URLEncoder.encode(accessToken, java.nio.charset.StandardCharsets.UTF_8))
                .build();
    }

    @GET
    @Path("/{fileId}/preview/content")
    @Produces("application/pdf")
    public Response previewContent(@RestPath String fileId, @QueryParam("access_token") String accessToken) {
        PreviewResolution preview = tokenFileQueryService.openPreview(fileId, accessToken);
        return DownloadResponses.inline(new DownloadFile(
                fileId,
                preview.originalFilename(),
                preview.mimeType(),
                preview.sizeBytes(),
                preview.inputStream()
        ));
    }

    @DELETE
    @Path("/{fileId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response delete(@RestPath String fileId, @QueryParam("access_token") String accessToken) {
        DeleteFileResult response = tokenFileCommandService.delete(fileId, accessToken);
        return Response.ok(ApiResponses.ok(response)).build();
    }
}
