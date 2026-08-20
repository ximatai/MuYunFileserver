package net.ximatai.muyun.fileserver.common.exception;

import jakarta.ws.rs.core.Response;

public class PayloadTooLargeException extends ClientException {

    public PayloadTooLargeException(String message) {
        super(Response.Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode(), message);
    }
}
