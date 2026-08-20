package net.ximatai.muyun.fileserver.common.filter;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import net.ximatai.muyun.fileserver.common.context.RequestMetadataHolder;

@Provider
public class RequestIdResponseFilter implements ContainerResponseFilter {

    @Inject
    RequestMetadataHolder requestMetadataHolder;

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        var metadata = requestMetadataHolder.get();
        if (metadata != null && metadata.traceId() != null) {
            responseContext.getHeaders().putSingle(RequestContextFilter.REQUEST_ID_HEADER, metadata.traceId());
        }
    }
}
