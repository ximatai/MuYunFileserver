package net.ximatai.muyun.fileserver.api;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import net.ximatai.muyun.fileserver.common.exception.NotFoundException;
import net.ximatai.muyun.fileserver.common.security.PublicEndpoint;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Path("/test-console")
@Blocking
@PublicEndpoint
public class TestConsoleResource {

    private static final String INDEX_PATH = "META-INF/test-console/index.html";
    private static final String ASSET_BASE_PLACEHOLDER = "/__TEST_CONSOLE_BASE__/test-console-assets/";

    @Inject
    FileServiceConfig config;

    @GET
    public Response index(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        if (!config.testConsole().enabled()) {
            throw new NotFoundException("resource not found");
        }
        try (InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(INDEX_PATH)) {
            if (inputStream == null) {
                return Response.serverError()
                        .type(MediaType.TEXT_PLAIN_TYPE)
                        .entity("test console assets are not available")
                        .build();
            }
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace(ASSET_BASE_PLACEHOLDER, assetBasePath(uriInfo, headers));
            return Response.ok(content, MediaType.TEXT_HTML_TYPE)
                    .header("Cache-Control", "no-store")
                    .build();
        } catch (IOException exception) {
            return Response.serverError()
                    .type(MediaType.TEXT_PLAIN_TYPE)
                    .entity("failed to load test console")
                    .build();
        }
    }

    private String assetBasePath(UriInfo uriInfo, HttpHeaders headers) {
        String forwardedPrefix = normalizePrefix(headers.getHeaderString("X-Forwarded-Prefix"));
        if (forwardedPrefix != null) {
            return forwardedPrefix + "/test-console-assets/";
        }
        String path = uriInfo.getPath();
        int markerIndex = path.indexOf("/test-console");
        if (markerIndex < 0) {
            return "/test-console-assets/";
        }
        String prefix = path.substring(0, markerIndex);
        return prefix.isBlank() ? "/test-console-assets/" : "/" + prefix + "/test-console-assets/";
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
