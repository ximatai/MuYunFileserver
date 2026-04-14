package net.ximatai.muyun.fileserver.common.filter;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import net.ximatai.muyun.fileserver.common.context.RequestContext;
import net.ximatai.muyun.fileserver.common.context.RequestContextHolder;
import net.ximatai.muyun.fileserver.common.exception.UnauthorizedException;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class RequestContextFilter implements ContainerRequestFilter {

    public static final String TENANT_ID_HEADER = "X-Tenant-Id";
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String CLIENT_ID_HEADER = "X-Client-Id";

    @Inject
    RequestContextHolder requestContextHolder;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String path = requestContext.getUriInfo().getPath();
        if (path.startsWith("q/")
                || path.startsWith("/q/")
                || path.startsWith("viewer/")
                || path.startsWith("/viewer/")
                || path.startsWith("view/")
                || path.startsWith("/view/")
                || path.equals("api/v1/public/files")
                || path.equals("/api/v1/public/files")
                || path.startsWith("api/v1/public/files/")
                || path.startsWith("/api/v1/public/files/")) {
            return;
        }

        String tenantId = header(requestContext, TENANT_ID_HEADER);
        String userId = header(requestContext, USER_ID_HEADER);
        String requestId = blankToNull(requestContext.getHeaderString(REQUEST_ID_HEADER));
        String clientId = blankToNull(requestContext.getHeaderString(CLIENT_ID_HEADER));

        requestContextHolder.set(new RequestContext(tenantId, userId, requestId, clientId));
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
