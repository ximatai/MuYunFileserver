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
import net.ximatai.muyun.fileserver.common.context.RequestMetadata;
import net.ximatai.muyun.fileserver.common.context.RequestMetadataHolder;
import org.jboss.logging.Logger;

@Provider
@Priority(Priorities.USER + 1)
public class UnhandledExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(UnhandledExceptionMapper.class);

    @Inject
    RequestContextHolder requestContextHolder;

    @Inject
    RequestMetadataHolder requestMetadataHolder;

    @Override
    public Response toResponse(Throwable exception) {
        LOG.error("request failed with unhandled exception", exception);
        return internalServerError();
    }

    private Response internalServerError() {
        // Keep the fallback response independent of the original exception so a
        // problematic exception message or cause can never affect serialization.
        try {
            return Response.serverError()
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(new ErrorResponse("internal server error", requestId()))
                    .build();
        } catch (RuntimeException ignored) {
            // Exception handling must not fail a second time while forming a response.
            return Response.serverError().build();
        }
    }

    private String requestId() {
        try {
            return requestContextHolder.getRequired().requestId();
        } catch (IllegalStateException ignored) {
            RequestMetadata metadata = requestMetadataHolder.get();
            return metadata == null ? null : metadata.traceId();
        }
    }
}
