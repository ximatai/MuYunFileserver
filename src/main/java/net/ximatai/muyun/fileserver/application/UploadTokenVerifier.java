package net.ximatai.muyun.fileserver.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.common.exception.UnauthorizedException;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;

@ApplicationScoped
public class UploadTokenVerifier {

    @Inject
    ObjectMapper objectMapper;

    @Inject
    SignedTokenVerifier signedTokenVerifier;

    private final Clock clock = Clock.systemUTC();

    public boolean isEnabled() {
        return signedTokenVerifier.isEnabled();
    }

    public UploadTokenClaims verify(String accessToken) {
        UploadTokenClaims claims = parseClaims(signedTokenVerifier.verifyPayload(accessToken, "upload"));
        validateClaims(claims);
        return claims;
    }

    private UploadTokenClaims parseClaims(byte[] payloadBytes) {
        try {
            UploadTokenClaims claims = objectMapper.readValue(payloadBytes, UploadTokenClaims.class);
            if (claims.subject() == null || claims.subject().isBlank()
                    || claims.tenantId() == null || claims.tenantId().isBlank()
                    || claims.expiresAtEpochSecond() <= 0) {
                throw new UnauthorizedException("invalid upload token");
            }
            return claims;
        } catch (IOException exception) {
            throw new UnauthorizedException("invalid upload token");
        }
    }

    private void validateClaims(UploadTokenClaims claims) {
        Instant expiresAt = Instant.ofEpochSecond(claims.expiresAtEpochSecond());
        Instant now = clock.instant().minus(signedTokenVerifier.tokenConfig().allowedClockSkew());
        if (expiresAt.isBefore(now)) {
            throw new UnauthorizedException("upload token expired");
        }
        signedTokenVerifier.tokenConfig().issuer()
                .filter(expectedIssuer -> !expectedIssuer.isBlank())
                .ifPresent(expectedIssuer -> {
                    if (!expectedIssuer.equals(claims.issuer())) {
                        throw new UnauthorizedException("invalid upload token");
                    }
                });
    }
}
