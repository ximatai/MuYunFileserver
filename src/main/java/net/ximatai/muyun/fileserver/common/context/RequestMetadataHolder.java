package net.ximatai.muyun.fileserver.common.context;

import jakarta.enterprise.context.RequestScoped;

@RequestScoped
public class RequestMetadataHolder {

    private RequestMetadata requestMetadata;

    public RequestMetadata get() {
        return requestMetadata;
    }

    public void set(RequestMetadata requestMetadata) {
        this.requestMetadata = requestMetadata;
    }
}
