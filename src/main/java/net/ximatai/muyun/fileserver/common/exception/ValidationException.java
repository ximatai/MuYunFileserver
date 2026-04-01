package net.ximatai.muyun.fileserver.common.exception;

import jakarta.ws.rs.core.Response;

public class ValidationException extends AppException {

    public ValidationException(String message) {
        super(Response.Status.BAD_REQUEST.getStatusCode(), message);
    }
}
