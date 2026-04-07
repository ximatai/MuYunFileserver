package net.ximatai.muyun.fileserver.api.dto;

import java.util.List;

public record PromoteFilesResponse(
        List<FileMetadataResponse> items
) {
}
