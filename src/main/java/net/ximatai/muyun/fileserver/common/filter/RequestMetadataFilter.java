package net.ximatai.muyun.fileserver.common.filter;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;
import net.ximatai.muyun.fileserver.common.context.RequestMetadata;
import net.ximatai.muyun.fileserver.common.context.RequestMetadataHolder;

import java.util.UUID;

/**
 * Captures only observability data.  It intentionally performs no authentication
 * because it also runs for requests that do not match an application resource.
 */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION - 1)
public class RequestMetadataFilter implements ContainerRequestFilter {

    @Inject
    RequestMetadataHolder requestMetadataHolder;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        requestMetadataHolder.set(new RequestMetadata(
                requestContext.getMethod(),
                requestContext.getUriInfo().getRequestUri().getRawPath(),
                requestId(requestContext.getHeaderString(RequestContextFilter.REQUEST_ID_HEADER))));
    }

    private String requestId(String value) {
        if (value == null) {
            return UUID.randomUUID().toString();
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? UUID.randomUUID().toString() : trimmed;
    }
}
