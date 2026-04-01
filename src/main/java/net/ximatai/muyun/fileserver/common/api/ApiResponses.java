package net.ximatai.muyun.fileserver.common.api;

import net.ximatai.muyun.fileserver.api.dto.SuccessResponse;

public final class ApiResponses {

    private ApiResponses() {
    }

    public static <T> SuccessResponse<T> ok(T data) {
        return new SuccessResponse<>(data);
    }
}
