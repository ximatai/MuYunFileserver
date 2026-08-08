package net.ximatai.muyun.fileserver.application;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.common.exception.UnauthorizedException;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/** Verifies the common signed-token envelope used by all public FileServer operations. */
@ApplicationScoped
public class SignedTokenVerifier {

    private static final String HMAC_SHA256 = "hmac-sha256";
    private static final String HMAC_SHA256_JCA = "HmacSHA256";

    @Inject
    FileServiceConfig config;

    @PostConstruct
    void validateConfig() {
        if (!config.token().enabled()) {
            return;
        }
        if (!HMAC_SHA256.equalsIgnoreCase(config.token().algorithm())) {
            throw new IllegalStateException("unsupported token algorithm: " + config.token().algorithm());
        }
        if (config.token().secret().isEmpty() || config.token().secret().get().isBlank()) {
            throw new IllegalStateException("mfs.token.secret must be configured when token mode is enabled");
        }
    }

    public boolean isEnabled() {
        return config.token().enabled();
    }

    public byte[] verifyPayload(String accessToken, String tokenKind) {
        if (!isEnabled()) {
            throw new UnauthorizedException(tokenKind + " token is disabled");
        }
        if (accessToken == null || accessToken.isBlank()) {
            throw new UnauthorizedException("missing access_token");
        }
        String[] segments = accessToken.split("\\.");
        if (segments.length != 2) {
            throw invalidToken(tokenKind);
        }
        byte[] payloadBytes = decode(segments[0], tokenKind);
        byte[] signatureBytes = decode(segments[1], tokenKind);
        if (!java.security.MessageDigest.isEqual(signatureBytes, sign(payloadBytes))) {
            throw invalidToken(tokenKind);
        }
        return payloadBytes;
    }

    public FileServiceConfig.Token tokenConfig() {
        return config.token();
    }

    private byte[] decode(String encoded, String tokenKind) {
        try {
            return Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw invalidToken(tokenKind);
        }
    }

    private byte[] sign(byte[] payloadBytes) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256_JCA);
            mac.init(new SecretKeySpec(config.token().secret().orElseThrow().getBytes(StandardCharsets.UTF_8), HMAC_SHA256_JCA));
            return mac.doFinal(payloadBytes);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("failed to initialize token verifier", exception);
        }
    }

    private UnauthorizedException invalidToken(String tokenKind) {
        return new UnauthorizedException("invalid " + tokenKind + " token");
    }
}
