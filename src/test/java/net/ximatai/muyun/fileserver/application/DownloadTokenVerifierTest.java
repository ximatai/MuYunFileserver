package net.ximatai.muyun.fileserver.application;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class DownloadTokenVerifierTest {

    private static final String SECRET = "test-download-token-secret";

    @Inject
    DownloadTokenVerifier verifier;

    @Test
    void shouldVerifyValidToken() throws Exception {
        String token = sign("""
                {"iss":"biz-app","sub":"u123","tenant_id":"tenant-a","file_id":"01ARZ3NDEKTSV4RRFFQ69G5FAV","exp":%d}
                """.formatted(Instant.now().plusSeconds(60).getEpochSecond()));

        DownloadTokenClaims claims = verifier.verify(token);

        assertEquals("biz-app", claims.issuer());
        assertEquals("u123", claims.subject());
        assertEquals(null, claims.purpose());
        assertEquals("tenant-a", claims.tenantId());
    }

    @Test
    void shouldRejectExpiredToken() throws Exception {
        String token = sign("""
                {"tenant_id":"tenant-a","file_id":"01ARZ3NDEKTSV4RRFFQ69G5FAV","exp":%d}
                """.formatted(Instant.now().minusSeconds(5).getEpochSecond()));

        assertThrows(RuntimeException.class, () -> verifier.verify(token));
    }

    @Test
    void shouldRejectInvalidSignature() throws Exception {
        String token = sign("""
                {"tenant_id":"tenant-a","file_id":"01ARZ3NDEKTSV4RRFFQ69G5FAV","exp":%d}
                """.formatted(Instant.now().plusSeconds(60).getEpochSecond())) + "tampered";

        assertThrows(RuntimeException.class, () -> verifier.verify(token));
    }

    private String sign(String payload) throws Exception {
        byte[] payloadBytes = payload.trim().getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signature = mac.doFinal(payloadBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes)
                + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }
}
