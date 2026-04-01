package net.ximatai.muyun.fileserver.api.dto;

import java.time.Instant;

public record DeleteFileResult(
        String id,
        String status,
        Instant deletedAt
) {
}
