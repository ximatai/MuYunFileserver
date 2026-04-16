package net.ximatai.muyun.fileserver.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

@ApplicationScoped
public class DownloadTokenSigner {

    private static final String HMAC_SHA256_JCA = "HmacSHA256";
    public static final String VIEWER_PURPOSE = "viewer";

    @Inject
    FileServiceConfig config;

    @Inject
    ObjectMapper objectMapper;

    private final Clock clock = Clock.systemUTC();

    public String signViewerToken(DownloadTokenClaims claims) {
        Instant now = clock.instant();
        long derivedExpiresAtEpochSecond = now.plus(config.token().viewerLinkTtl()).getEpochSecond();
        DownloadTokenClaims derivedClaims = new DownloadTokenClaims(
                claims.issuer(),
                claims.subject(),
                VIEWER_PURPOSE,
                claims.tenantId(),
                claims.fileId(),
                derivedExpiresAtEpochSecond,
                now.getEpochSecond(),
                null
        );
        return sign(derivedClaims);
    }

    public String sign(DownloadTokenClaims claims) {
        byte[] payloadBytes = serialize(claims);
        byte[] signatureBytes = signPayload(payloadBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes)
                + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes);
    }

    private byte[] serialize(DownloadTokenClaims claims) {
        try {
            return objectMapper.writeValueAsBytes(claims);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize download token claims", exception);
        }
    }

    private byte[] signPayload(byte[] payloadBytes) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256_JCA);
            mac.init(new SecretKeySpec(config.token().secret().orElseThrow().getBytes(StandardCharsets.UTF_8), HMAC_SHA256_JCA));
            return mac.doFinal(payloadBytes);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("failed to sign download token", exception);
        }
    }
}
