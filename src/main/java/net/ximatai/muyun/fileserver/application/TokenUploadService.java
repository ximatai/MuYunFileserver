package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.api.dto.UploadFilesResponse;
import net.ximatai.muyun.fileserver.common.context.RequestContext;
import net.ximatai.muyun.fileserver.common.exception.ForbiddenException;
import net.ximatai.muyun.fileserver.common.exception.NotFoundException;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;

@ApplicationScoped
public class TokenUploadService {

    private static final String UPLOAD_PURPOSE = "upload";

    @Inject
    UploadTokenVerifier uploadTokenVerifier;

    @Inject
    UploadService uploadService;

    public UploadFilesResponse upload(MultipartFormDataInput input, String accessToken) {
        if (!uploadTokenVerifier.isEnabled()) {
            throw new NotFoundException("resource not found");
        }

        UploadTokenClaims claims = uploadTokenVerifier.verify(accessToken);
        if (!UPLOAD_PURPOSE.equals(claims.purpose())) {
            throw new ForbiddenException("upload token purpose is not valid for upload");
        }

        RequestContext requestContext = new RequestContext(claims.tenantId(), claims.subject(), null, null);
        return uploadService.upload(input, requestContext, false);
    }
}
