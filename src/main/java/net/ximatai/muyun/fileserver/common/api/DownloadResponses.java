package net.ximatai.muyun.fileserver.common.api;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import net.ximatai.muyun.fileserver.application.DownloadFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class DownloadResponses {

    private DownloadResponses() {
    }

    public static Response ok(DownloadFile file) {
        return build(file, "attachment");
    }

    public static Response inline(DownloadFile file) {
        return build(file, "inline");
    }

    private static Response build(DownloadFile file, String dispositionType) {
        return Response.ok((jakarta.ws.rs.core.StreamingOutput) output -> transfer(file.inputStream(), output))
                .type(file.mimeType())
                .header("Content-Length", file.sizeBytes())
                .header("Content-Disposition", contentDisposition(dispositionType, file.originalFilename()))
                .build();
    }

    private static void transfer(InputStream inputStream, java.io.OutputStream outputStream) throws IOException {
        try (InputStream in = inputStream) {
            in.transferTo(outputStream);
        } catch (IOException exception) {
            throw new WebApplicationException(exception);
        }
    }

    private static String contentDisposition(String dispositionType, String originalFilename) {
        String sanitized = originalFilename
                .replace("\\", "_")
                .replace("\"", "_")
                .replace("\r", "_")
                .replace("\n", "_");
        String encoded = URLEncoder.encode(sanitized, StandardCharsets.UTF_8).replace("+", "%20");
        return dispositionType + "; filename=\"" + sanitized + "\"; filename*=UTF-8''" + encoded;
    }
}
