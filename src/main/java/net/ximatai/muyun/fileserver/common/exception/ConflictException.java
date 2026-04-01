package net.ximatai.muyun.fileserver.common.exception;

import jakarta.ws.rs.core.Response;

public class ConflictException extends AppException {

    public ConflictException(String message) {
        super(Response.Status.CONFLICT.getStatusCode(), message);
    }
}
