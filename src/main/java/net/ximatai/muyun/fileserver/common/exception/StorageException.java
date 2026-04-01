package net.ximatai.muyun.fileserver.common.exception;

import jakarta.ws.rs.core.Response;

public class StorageException extends AppException {

    public StorageException(String message) {
        super(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), message);
    }

    public StorageException(String message, Throwable cause) {
        super(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), message);
        initCause(cause);
    }
}
