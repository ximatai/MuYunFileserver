package net.ximatai.muyun.fileserver.api.dto;

public record SuccessResponse<T>(
        boolean success,
        T data
) {
    public SuccessResponse(T data) {
        this(true, data);
    }
}
