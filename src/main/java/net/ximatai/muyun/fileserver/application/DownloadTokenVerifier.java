package net.ximatai.muyun.fileserver.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.common.exception.UnauthorizedException;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

@ApplicationScoped
public class DownloadTokenVerifier {

    private static final String HMAC_SHA256 = "hmac-sha256";
    private static final String HMAC_SHA256_JCA = "HmacSHA256";

    @Inject
    FileServiceConfig config;

    @Inject
    ObjectMapper objectMapper;

    private final Clock clock = Clock.systemUTC();

    @PostConstruct
    void validateConfig() {
        if (!config.downloadToken().enabled()) {
            return;
        }
        if (!HMAC_SHA256.equalsIgnoreCase(config.downloadToken().algorithm())) {
            throw new IllegalStateException("unsupported download token algorithm: " + config.downloadToken().algorithm());
        }
        if (config.downloadToken().secret().isEmpty() || config.downloadToken().secret().get().isBlank()) {
            throw new IllegalStateException("mfs.download-token.secret must be configured when download token is enabled");
        }
    }

    public boolean isEnabled() {
        return config.downloadToken().enabled();
    }

    public DownloadTokenClaims verify(String accessToken) {
        if (!isEnabled()) {
            throw new UnauthorizedException("download token is disabled");
        }
        if (accessToken == null || accessToken.isBlank()) {
            throw new UnauthorizedException("missing access_token");
        }

        String[] segments = accessToken.split("\\.");
        if (segments.length != 2) {
            throw new UnauthorizedException("invalid download token");
        }

        byte[] payloadBytes = decode(segments[0]);
        byte[] signatureBytes = decode(segments[1]);
        byte[] expectedSignature = sign(payloadBytes);
        if (!java.security.MessageDigest.isEqual(signatureBytes, expectedSignature)) {
            throw new UnauthorizedException("invalid download token");
        }

        DownloadTokenClaims claims = parseClaims(payloadBytes);
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
        Instant now = clock.instant().minus(config.downloadToken().allowedClockSkew());
        if (expiresAt.isBefore(now)) {
            throw new UnauthorizedException("download token expired");
        }

        config.downloadToken().issuer()
                .filter(expectedIssuer -> !expectedIssuer.isBlank())
                .ifPresent(expectedIssuer -> {
                    if (!expectedIssuer.equals(claims.issuer())) {
                        throw new UnauthorizedException("invalid download token");
                    }
                });
    }

    private byte[] decode(String encoded) {
        try {
            return Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new UnauthorizedException("invalid download token");
        }
    }

    private byte[] sign(byte[] payloadBytes) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256_JCA);
            mac.init(new SecretKeySpec(config.downloadToken().secret().orElseThrow().getBytes(StandardCharsets.UTF_8), HMAC_SHA256_JCA));
            return mac.doFinal(payloadBytes);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("failed to initialize download token verifier", exception);
        }
    }
}
