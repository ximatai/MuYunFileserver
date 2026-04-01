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
import org.jboss.logging.Logger;

@Provider
@Priority(Priorities.USER + 1)
public class UnhandledExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(UnhandledExceptionMapper.class);

    @Inject
    RequestContextHolder requestContextHolder;

    @Override
    public Response toResponse(Throwable exception) {
        LOG.error("request failed with unhandled exception", exception);
        return Response.serverError()
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new ErrorResponse("internal server error", requestId()))
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
