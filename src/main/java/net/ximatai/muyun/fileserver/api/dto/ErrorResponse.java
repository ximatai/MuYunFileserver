package net.ximatai.muyun.fileserver.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        boolean success,
        String message,
        String requestId
) {
    public ErrorResponse(String message, String requestId) {
        this(false, message, requestId);
    }
}
