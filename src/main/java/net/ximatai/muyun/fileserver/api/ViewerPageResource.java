package net.ximatai.muyun.fileserver.api;

import io.smallrye.common.annotation.Blocking;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Path("/view")
@Blocking
public class ViewerPageResource {

    private static final String INDEX_PATH = "META-INF/resources/viewer/index.html";
    private static final String VIEWER_BASE_PLACEHOLDER = "/__VIEWER_BASE__/viewer/";
    private static final String VIEWER_ROOT_PLACEHOLDER = "/__VIEWER_BASE__/";

    @GET
    @Path("/files/{fileId}")
    public Response privateViewer(@PathParam("fileId") String fileId, @Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return index(uriInfo, headers);
    }

    @GET
    @Path("/public/files/{fileId}")
    public Response publicViewer(@PathParam("fileId") String fileId, @Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return index(uriInfo, headers);
    }

    private Response index(UriInfo uriInfo, HttpHeaders headers) {
        try (InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(INDEX_PATH)) {
            if (inputStream == null) {
                return Response.serverError()
                        .type(MediaType.TEXT_PLAIN_TYPE)
                        .entity("viewer assets are not available")
                        .build();
            }
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace(VIEWER_BASE_PLACEHOLDER, viewerBasePath(uriInfo, headers))
                    .replace(VIEWER_ROOT_PLACEHOLDER, viewerBasePath(uriInfo, headers));
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

    private String viewerBasePath(UriInfo uriInfo, HttpHeaders headers) {
        String forwardedPrefix = normalizePrefix(headers.getHeaderString("X-Forwarded-Prefix"));
        if (forwardedPrefix != null) {
            return forwardedPrefix + "/viewer/";
        }
        String path = uriInfo.getPath();
        int markerIndex = path.indexOf("/view/");
        if (markerIndex < 0) {
            return "/viewer/";
        }
        String prefix = path.substring(0, markerIndex);
        if (prefix.isBlank()) {
            return "/viewer/";
        }
        return "/" + prefix + "/viewer/";
    }

    private String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank() || "/".equals(prefix.trim())) {
            return null;
        }
        String normalized = prefix.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
