package net.ximatai.muyun.fileserver.api.dto;

import java.util.List;

public final class FileReceptionRequests {
    private FileReceptionRequests() { }
    public record Create(String idempotencyKey, int maxObjects, long maxBytes, long expiresInSeconds) { }
    /** Keys are relative to the server-generated reception prefix. SHA256 may be unknown at submission. */
    public record ObjectEntry(String key, String filename, long sizeBytes, String sha256) { }
    public record Confirm(List<ObjectEntry> objects) { }
}
