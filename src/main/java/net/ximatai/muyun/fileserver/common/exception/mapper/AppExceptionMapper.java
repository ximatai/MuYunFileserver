package net.ximatai.muyun.fileserver.common.exception.mapper;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import net.ximatai.muyun.fileserver.api.dto.ErrorResponse;
import net.ximatai.muyun.fileserver.common.context.RequestContextHolder;
import net.ximatai.muyun.fileserver.common.exception.AppException;
import org.jboss.logging.Logger;

@Provider
@Priority(Priorities.USER)
public class AppExceptionMapper implements ExceptionMapper<AppException> {

    private static final Logger LOG = Logger.getLogger(AppExceptionMapper.class);

    @Inject
    RequestContextHolder requestContextHolder;

    @Override
    public Response toResponse(AppException exception) {
        LOG.debugf("request failed with controlled exception: status=%d, message=%s",
                exception.status(), exception.getMessage());
        return Response.status(exception.status())
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new ErrorResponse(exception.getMessage(), requestId()))
                .build();
    }

    private String requestId() {
        try {
            return requestContextHolder.getRequired().requestId();
        } catch (IllegalStateException ignored) {
            return null;
        }
    }
}
