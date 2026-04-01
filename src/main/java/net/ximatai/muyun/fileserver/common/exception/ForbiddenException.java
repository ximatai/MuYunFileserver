package net.ximatai.muyun.fileserver.common.exception;

import jakarta.ws.rs.core.Response;

public class ForbiddenException extends AppException {

    public ForbiddenException(String message) {
        super(Response.Status.FORBIDDEN.getStatusCode(), message);
    }
}
