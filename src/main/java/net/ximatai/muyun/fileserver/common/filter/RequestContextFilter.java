package net.ximatai.muyun.fileserver.common.filter;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import net.ximatai.muyun.fileserver.common.context.RequestContext;
import net.ximatai.muyun.fileserver.common.context.RequestContextHolder;
import net.ximatai.muyun.fileserver.common.context.RequestMetadataHolder;
import net.ximatai.muyun.fileserver.common.exception.UnauthorizedException;
import net.ximatai.muyun.fileserver.common.security.PublicEndpoint;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class RequestContextFilter implements ContainerRequestFilter {

    public static final String TENANT_ID_HEADER = "X-Tenant-Id";
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String CLIENT_ID_HEADER = "X-Client-Id";

    @Inject
    RequestContextHolder requestContextHolder;

    @Inject
    RequestMetadataHolder requestMetadataHolder;

    @Context
    ResourceInfo resourceInfo;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String path = requestContext.getUriInfo().getPath();
        if (isInfrastructureEndpoint(path) || isPublicEndpoint()) {
            return;
        }

        String tenantId = header(requestContext, TENANT_ID_HEADER);
        String userId = header(requestContext, USER_ID_HEADER);
        String requestId = requestMetadataHolder.get().traceId();
        String clientId = blankToNull(requestContext.getHeaderString(CLIENT_ID_HEADER));

        requestContextHolder.set(new RequestContext(tenantId, userId, requestId, clientId));
    }

    private boolean isInfrastructureEndpoint(String path) {
        return path.startsWith("q/")
                || path.startsWith("/q/")
                || path.startsWith("viewer/")
                || path.startsWith("/viewer/")
                || path.startsWith("test-console-assets/")
                || path.startsWith("/test-console-assets/");
    }

    private boolean isPublicEndpoint() {
        return resourceInfo != null
                && ((resourceInfo.getResourceMethod() != null
                && resourceInfo.getResourceMethod().isAnnotationPresent(PublicEndpoint.class))
                || (resourceInfo.getResourceClass() != null
                && resourceInfo.getResourceClass().isAnnotationPresent(PublicEndpoint.class)));
    }

    private String header(ContainerRequestContext requestContext, String headerName) {
        String value = blankToNull(requestContext.getHeaderString(headerName));
        if (value == null) {
            throw new UnauthorizedException("missing required identity header: " + headerName);
        }
        return value;
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
