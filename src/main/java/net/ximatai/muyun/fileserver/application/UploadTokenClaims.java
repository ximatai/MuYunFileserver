package net.ximatai.muyun.fileserver.application;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UploadTokenClaims(
        @JsonProperty("iss")
        String issuer,
        @JsonProperty("sub")
        String subject,
        @JsonProperty("purpose")
        String purpose,
        @JsonProperty("tenant_id")
        String tenantId,
        @JsonProperty("exp")
        long expiresAtEpochSecond,
        @JsonProperty("iat")
        Long issuedAtEpochSecond,
        @JsonProperty("jti")
        String tokenId
) {
}
