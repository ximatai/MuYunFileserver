package net.ximatai.muyun.fileserver.api.dto;

import java.time.Instant;

public record DownloadLinkResponse(
        String fileId,
        String downloadPath,
        Instant expiresAt
) {
}
