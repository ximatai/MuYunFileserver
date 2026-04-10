package net.ximatai.muyun.fileserver.common.exception;

import jakarta.ws.rs.core.Response;

public class ServiceUnavailableException extends AppException {

    public ServiceUnavailableException(String message) {
        super(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), message);
    }
}
