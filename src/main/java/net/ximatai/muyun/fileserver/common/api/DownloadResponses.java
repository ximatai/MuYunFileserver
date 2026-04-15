package net.ximatai.muyun.fileserver.common.api;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import net.ximatai.muyun.fileserver.application.DownloadFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DownloadResponses {

    private static final Pattern RANGE_PATTERN = Pattern.compile("^bytes=(\\d*)-(\\d*)$");

    private DownloadResponses() {
    }

    public static Response ok(DownloadFile file) {
        return build(file, "attachment");
    }

    public static Response ok(DownloadFile file, String rangeHeader) {
        return build(file, "attachment", rangeHeader);
    }

    public static Response inline(DownloadFile file) {
        return build(file, "inline");
    }

    public static Response inline(DownloadFile file, String rangeHeader) {
        return build(file, "inline", rangeHeader);
    }

    private static Response build(DownloadFile file, String dispositionType) {
        return build(file, dispositionType, null);
    }

    private static Response build(DownloadFile file, String dispositionType, String rangeHeader) {
        if (!file.supportsRange()) {
            return Response.ok((jakarta.ws.rs.core.StreamingOutput) output -> transfer(file.inputStreamSupplier().open(), output))
                    .type(file.mimeType())
                    .header("Content-Length", file.sizeBytes())
                    .header("Content-Disposition", contentDisposition(dispositionType, file.originalFilename()))
                    .build();
        }

        ByteRange range = parseRange(rangeHeader, file.sizeBytes());
        if (range == null) {
            Response.ResponseBuilder builder = Response.ok((jakarta.ws.rs.core.StreamingOutput) output -> transfer(file.inputStreamSupplier().open(), output))
                    .type(file.mimeType())
                    .header("Content-Length", file.sizeBytes())
                    .header("Content-Disposition", contentDisposition(dispositionType, file.originalFilename()));
            builder.header("Accept-Ranges", "bytes");
            return builder.build();
        }

        return Response.status(Response.Status.PARTIAL_CONTENT)
                .entity((jakarta.ws.rs.core.StreamingOutput) output -> transfer(file.rangeInputStreamSupplier().open(range.start(), range.length()), output))
                .type(file.mimeType())
                .header("Accept-Ranges", "bytes")
                .header("Content-Length", range.length())
                .header("Content-Range", "bytes " + range.start() + "-" + range.end() + "/" + file.sizeBytes())
                .header("Content-Disposition", contentDisposition(dispositionType, file.originalFilename()))
                .build();
    }

    public static Response invalidRange(long fileSize) {
        return Response.status(Response.Status.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header("Accept-Ranges", "bytes")
                .header("Content-Range", "bytes */" + fileSize)
                .build();
    }

    private static ByteRange parseRange(String rangeHeader, long fileSize) {
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return null;
        }
        Matcher matcher = RANGE_PATTERN.matcher(rangeHeader.trim());
        if (!matcher.matches()) {
            throw new InvalidRangeException();
        }
        String startGroup = matcher.group(1);
        String endGroup = matcher.group(2);
        if (startGroup.isBlank() && endGroup.isBlank()) {
            throw new InvalidRangeException();
        }

        long start;
        long end;
        try {
            if (startGroup.isBlank()) {
                long suffixLength = Long.parseLong(endGroup);
                if (suffixLength <= 0) {
                    throw new InvalidRangeException();
                }
                if (suffixLength >= fileSize) {
                    start = 0;
                } else {
                    start = fileSize - suffixLength;
                }
                end = fileSize - 1;
            } else {
                start = Long.parseLong(startGroup);
                end = endGroup.isBlank() ? fileSize - 1 : Long.parseLong(endGroup);
            }
        } catch (NumberFormatException exception) {
            throw new InvalidRangeException();
        }

        if (fileSize <= 0 || start < 0 || end < start || start >= fileSize) {
            throw new InvalidRangeException();
        }
        end = Math.min(end, fileSize - 1);
        return new ByteRange(start, end);
    }

    public static final class InvalidRangeException extends RuntimeException {
    }

    private record ByteRange(long start, long end) {
        long length() {
            return end - start + 1;
        }
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
