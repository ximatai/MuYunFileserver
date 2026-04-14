package net.ximatai.muyun.fileserver.api.dto;

import net.ximatai.muyun.fileserver.application.ViewerType;

public record FileViewResponse(
        String fileId,
        String displayName,
        ViewerType viewerType,
        String sourceMimeType,
        String contentMimeType,
        String contentUrl,
        String downloadUrl,
        ViewCapabilitiesResponse capabilities
) {
}
