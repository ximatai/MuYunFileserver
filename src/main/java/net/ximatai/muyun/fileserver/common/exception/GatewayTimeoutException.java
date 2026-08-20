package net.ximatai.muyun.fileserver.common.exception;

import jakarta.ws.rs.core.Response;

public class GatewayTimeoutException extends ServerException {

    public GatewayTimeoutException(String message) {
        super(Response.Status.GATEWAY_TIMEOUT.getStatusCode(), message);
    }
}
