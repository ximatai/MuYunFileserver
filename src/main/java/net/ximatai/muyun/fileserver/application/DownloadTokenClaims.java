package net.ximatai.muyun.fileserver.application;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DownloadTokenClaims(
        @JsonProperty("iss")
        String issuer,
        @JsonProperty("sub")
        String subject,
        @JsonProperty("tenant_id")
        String tenantId,
        @JsonProperty("file_id")
        String fileId,
        @JsonProperty("exp")
        long expiresAtEpochSecond,
        @JsonProperty("iat")
        Long issuedAtEpochSecond,
        @JsonProperty("jti")
        String tokenId
) {
}
