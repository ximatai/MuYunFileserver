package net.ximatai.muyun.fileserver.common.exception;

import jakarta.ws.rs.core.Response;

public class NotFoundException extends AppException {

    public NotFoundException(String message) {
        super(Response.Status.NOT_FOUND.getStatusCode(), message);
    }
}
