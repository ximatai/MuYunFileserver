package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.api.dto.DeleteFileResult;
import net.ximatai.muyun.fileserver.api.dto.PromoteFilesResponse;
import net.ximatai.muyun.fileserver.common.context.RequestContext;
import net.ximatai.muyun.fileserver.common.exception.ForbiddenException;
import net.ximatai.muyun.fileserver.common.exception.NotFoundException;
import net.ximatai.muyun.fileserver.common.exception.ValidationException;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;
import net.ximatai.muyun.fileserver.infrastructure.ulid.UlidGenerator;

import java.util.List;

/** Authenticates token commands before entering the shared file lifecycle. */
@ApplicationScoped
public class TokenFileCommandService {
    @Inject
    DownloadTokenVerifier downloadTokenVerifier;
    @Inject
    FileCommandService commands;
    @Inject
    UlidGenerator ulidGenerator;
    @Inject
    FileServiceConfig config;

    public DeleteFileResult delete(String fileId, String accessToken) {
        return commands.delete(fileId, authorize(fileId, accessToken, "delete"));
    }

    public PromoteFilesResponse promote(String fileId, String accessToken) {
        return commands.promote(List.of(fileId), authorize(fileId, accessToken, "promote"));
    }

    private RequestContext authorize(String fileId, String accessToken, String purpose) {
        if (!config.token().enabled()) throw new NotFoundException("resource not found");
        if (!ulidGenerator.isValid(fileId)) throw new ValidationException("invalid fileId");
        DownloadTokenClaims claims = downloadTokenVerifier.verify(accessToken);
        if (!purpose.equals(claims.purpose()) || !fileId.equals(claims.fileId())) {
            throw new ForbiddenException(purpose + " token is not valid for requested file");
        }
        return new RequestContext(claims.tenantId(), claims.subject(), null, claims.issuer());
    }
}
