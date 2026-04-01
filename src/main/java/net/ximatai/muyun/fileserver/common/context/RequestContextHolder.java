package net.ximatai.muyun.fileserver.common.context;

import jakarta.enterprise.context.RequestScoped;

@RequestScoped
public class RequestContextHolder {

    private RequestContext requestContext;

    public RequestContext getRequired() {
        if (requestContext == null) {
            throw new IllegalStateException("request context is not initialized");
        }
        return requestContext;
    }

    public void set(RequestContext requestContext) {
        this.requestContext = requestContext;
    }
}
