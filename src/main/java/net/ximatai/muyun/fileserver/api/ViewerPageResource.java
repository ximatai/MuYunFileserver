package net.ximatai.muyun.fileserver.api;

import io.smallrye.common.annotation.Blocking;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;

@Path("/view")
@Blocking
public class ViewerPageResource {

    private static final String INDEX_PATH = "META-INF/resources/viewer/index.html";

    @GET
    @Path("/files/{fileId}")
    public Response privateViewer(@PathParam("fileId") String fileId) {
        return index();
    }

    @GET
    @Path("/public/files/{fileId}")
    public Response publicViewer(@PathParam("fileId") String fileId) {
        return index();
    }

    private Response index() {
        try (InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(INDEX_PATH)) {
            if (inputStream == null) {
                return Response.serverError()
                        .type(MediaType.TEXT_PLAIN_TYPE)
                        .entity("viewer assets are not available")
                        .build();
            }
            byte[] content = inputStream.readAllBytes();
            return Response.ok(content, MediaType.TEXT_HTML_TYPE)
                    .header("Cache-Control", "no-store")
                    .build();
        } catch (IOException exception) {
            return Response.serverError()
                    .type(MediaType.TEXT_PLAIN_TYPE)
                    .entity("failed to load viewer assets")
                    .build();
        }
    }
}
