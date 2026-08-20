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
import net.ximatai.muyun.fileserver.common.exception.AppException;
import net.ximatai.muyun.fileserver.common.exception.ClientException;
import org.jboss.logging.Logger;

@Provider
@Priority(Priorities.USER)
public class AppExceptionMapper implements ExceptionMapper<AppException> {

    private static final Logger LOG = Logger.getLogger(AppExceptionMapper.class);

    @Inject
    RequestContextHolder requestContextHolder;

    @Inject
    RequestMetadataHolder requestMetadataHolder;

    @Override
    public Response toResponse(AppException exception) {
        if (exception instanceof ClientException) {
            if (LOG.isDebugEnabled()) {
                RequestMetadata request = requestMetadataHolder.get();
                LOG.debugf("expected client error: method=%s, path=%s, status=%d, traceId=%s",
                        request == null ? null : request.method(),
                        request == null ? null : request.path(),
                        exception.status(),
                        request == null ? null : request.traceId());
            }
        } else {
            LOG.error("request failed with controlled server exception", exception);
        }
        return Response.status(exception.status())
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new ErrorResponse(exception.getMessage(), requestId()))
                .build();
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
