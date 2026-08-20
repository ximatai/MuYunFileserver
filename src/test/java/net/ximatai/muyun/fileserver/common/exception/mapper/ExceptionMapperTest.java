package net.ximatai.muyun.fileserver.common.exception.mapper;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import net.ximatai.muyun.fileserver.api.dto.ErrorResponse;
import net.ximatai.muyun.fileserver.common.context.RequestContextHolder;
import net.ximatai.muyun.fileserver.common.context.RequestMetadata;
import net.ximatai.muyun.fileserver.common.context.RequestMetadataHolder;
import net.ximatai.muyun.fileserver.common.exception.StorageException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ExceptionMapperTest {

    @Test
    void clientExceptionKeeps4xxAndUsesTraceIdWhenNoIdentityContextExists() {
        AppExceptionMapper mapper = appExceptionMapper("trace-client");

        Response response = mapper.toResponse(new net.ximatai.muyun.fileserver.common.exception.NotFoundException("file not found"));

        assertEquals(404, response.getStatus());
        ErrorResponse error = (ErrorResponse) response.getEntity();
        assertEquals("file not found", error.message());
        assertEquals("trace-client", error.requestId());
    }

    @Test
    void controlledServerExceptionKeeps5xxAndUsesTraceIdWhenNoIdentityContextExists() {
        AppExceptionMapper mapper = appExceptionMapper("trace-server");

        Response response = mapper.toResponse(new StorageException("storage unavailable"));

        assertEquals(500, response.getStatus());
        assertEquals("trace-server", ((ErrorResponse) response.getEntity()).requestId());
    }

    @Test
    void unknownExceptionProducesSafe500WithTraceId() {
        UnhandledExceptionMapper mapper = new UnhandledExceptionMapper();
        mapper.requestContextHolder = new RequestContextHolder();
        mapper.requestMetadataHolder = requestMetadata("trace-unhandled");

        Response response = mapper.toResponse(new IllegalStateException("unexpected"));

        assertEquals(500, response.getStatus());
        ErrorResponse error = (ErrorResponse) response.getEntity();
        assertEquals("internal server error", error.message());
        assertEquals("trace-unhandled", error.requestId());
    }

    @Test
    void webApplicationExceptionPreservesItsOriginalResponse() {
        Response original = Response.status(404).header("X-Test", "preserved").entity("not found").build();
        WebApplicationExceptionMapper mapper = new WebApplicationExceptionMapper();
        mapper.requestMetadataHolder = requestMetadata("trace-web");

        assertSame(original, mapper.toResponse(new NotFoundException(original)));
    }

    @Test
    void serverWebApplicationExceptionUsesStandardErrorResponse() {
        WebApplicationExceptionMapper mapper = new WebApplicationExceptionMapper();
        mapper.requestContextHolder = new RequestContextHolder();
        mapper.requestMetadataHolder = requestMetadata("trace-web-server");

        Response response = mapper.toResponse(new jakarta.ws.rs.WebApplicationException(new IllegalStateException("stream failed")));

        assertEquals(500, response.getStatus());
        ErrorResponse error = (ErrorResponse) response.getEntity();
        assertEquals("internal server error", error.message());
        assertEquals("trace-web-server", error.requestId());
    }

    private AppExceptionMapper appExceptionMapper(String traceId) {
        AppExceptionMapper mapper = new AppExceptionMapper();
        mapper.requestContextHolder = new RequestContextHolder();
        mapper.requestMetadataHolder = requestMetadata(traceId);
        return mapper;
    }

    private RequestMetadataHolder requestMetadata(String traceId) {
        RequestMetadataHolder holder = new RequestMetadataHolder();
        holder.set(new RequestMetadata("GET", "/test", traceId));
        return holder;
    }
}
