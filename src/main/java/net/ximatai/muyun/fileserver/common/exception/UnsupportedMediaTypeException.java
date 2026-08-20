package net.ximatai.muyun.fileserver.common.exception;

import jakarta.ws.rs.core.Response;

public class UnsupportedMediaTypeException extends ClientException {

    public UnsupportedMediaTypeException(String message) {
        super(Response.Status.UNSUPPORTED_MEDIA_TYPE.getStatusCode(), message);
    }
}
