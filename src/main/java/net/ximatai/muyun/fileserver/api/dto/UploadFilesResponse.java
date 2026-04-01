package net.ximatai.muyun.fileserver.api.dto;

import java.util.List;

public record UploadFilesResponse(
        List<UploadFileItemResponse> items
) {
}
