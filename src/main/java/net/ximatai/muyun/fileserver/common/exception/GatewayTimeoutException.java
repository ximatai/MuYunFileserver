package net.ximatai.muyun.fileserver.common.exception;

import jakarta.ws.rs.core.Response;

public class GatewayTimeoutException extends AppException {

    public GatewayTimeoutException(String message) {
        super(Response.Status.GATEWAY_TIMEOUT.getStatusCode(), message);
    }
}
