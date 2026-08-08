package net.ximatai.muyun.fileserver.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.common.exception.UnauthorizedException;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;

@ApplicationScoped
public class DownloadTokenVerifier {

    @Inject
    ObjectMapper objectMapper;

    @Inject
    SignedTokenVerifier signedTokenVerifier;

    private final Clock clock = Clock.systemUTC();

    public boolean isEnabled() {
        return signedTokenVerifier.isEnabled();
    }

    public DownloadTokenClaims verify(String accessToken) {
        DownloadTokenClaims claims = parseClaims(signedTokenVerifier.verifyPayload(accessToken, "download"));
        validateClaims(claims);
        return claims;
    }

    private DownloadTokenClaims parseClaims(byte[] payloadBytes) {
        try {
            DownloadTokenClaims claims = objectMapper.readValue(payloadBytes, DownloadTokenClaims.class);
            if (claims.tenantId() == null || claims.tenantId().isBlank()
                    || claims.fileId() == null || claims.fileId().isBlank()
                    || claims.expiresAtEpochSecond() <= 0) {
                throw new UnauthorizedException("invalid download token");
            }
            return claims;
        } catch (IOException exception) {
            throw new UnauthorizedException("invalid download token");
        }
    }

    private void validateClaims(DownloadTokenClaims claims) {
        Instant expiresAt = Instant.ofEpochSecond(claims.expiresAtEpochSecond());
        Instant now = clock.instant().minus(signedTokenVerifier.tokenConfig().allowedClockSkew());
        if (expiresAt.isBefore(now)) {
            throw new UnauthorizedException("download token expired");
        }
        signedTokenVerifier.tokenConfig().issuer()
                .filter(expectedIssuer -> !expectedIssuer.isBlank())
                .ifPresent(expectedIssuer -> {
                    if (!expectedIssuer.equals(claims.issuer())) {
                        throw new UnauthorizedException("invalid download token");
                    }
                });
    }
}
