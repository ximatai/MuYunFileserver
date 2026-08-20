package net.ximatai.muyun.fileserver.common.exception;

import jakarta.ws.rs.core.Response;

public class UnauthorizedException extends ClientException {

    public UnauthorizedException(String message) {
        super(Response.Status.UNAUTHORIZED.getStatusCode(), message);
    }
}
