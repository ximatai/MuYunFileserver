package net.ximatai.muyun.fileserver.api;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.ximatai.muyun.fileserver.api.dto.DeleteFileResult;
import net.ximatai.muyun.fileserver.api.dto.FileMetadataResponse;
import net.ximatai.muyun.fileserver.api.dto.UploadFilesResponse;
import net.ximatai.muyun.fileserver.application.DownloadFile;
import net.ximatai.muyun.fileserver.application.FileCommandService;
import net.ximatai.muyun.fileserver.application.FileQueryService;
import net.ximatai.muyun.fileserver.application.UploadService;
import net.ximatai.muyun.fileserver.common.api.ApiResponses;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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
        return Response.status(Response.Status.CREATED)
                .entity(ApiResponses.ok(response))
                .build();
    }

    @GET
    @Path("/{fileId}")
    public Response getMetadata(@RestPath String fileId) {
        FileMetadataResponse response = fileQueryService.getMetadata(fileId);
        return Response.ok(ApiResponses.ok(response)).build();
    }

    @GET
    @Path("/{fileId}/download")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response download(@RestPath String fileId) {
        DownloadFile file = fileQueryService.openDownload(fileId);
        return Response.ok((jakarta.ws.rs.core.StreamingOutput) output -> transfer(file.inputStream(), output))
                .type(file.mimeType())
                .header("Content-Length", file.sizeBytes())
                .header("Content-Disposition", contentDisposition(file.originalFilename()))
                .build();
    }

    @DELETE
    @Path("/{fileId}")
    public Response delete(@RestPath String fileId) {
        DeleteFileResult response = fileCommandService.delete(fileId);
        return Response.ok(ApiResponses.ok(response)).build();
    }

    private void transfer(InputStream inputStream, java.io.OutputStream outputStream) throws IOException {
        try (InputStream in = inputStream) {
            in.transferTo(outputStream);
        } catch (IOException exception) {
            throw new WebApplicationException(exception);
        }
    }

    private String contentDisposition(String originalFilename) {
        String sanitized = originalFilename
                .replace("\\", "_")
                .replace("\"", "_")
                .replace("\r", "_")
                .replace("\n", "_");
        String encoded = URLEncoder.encode(sanitized, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + sanitized + "\"; filename*=UTF-8''" + encoded;
    }
}
