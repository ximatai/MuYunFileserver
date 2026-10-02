package net.ximatai.muyun.fileserver.domain.file;

public record FileReception(String id, String tenantId, String idempotencyKey, String prefix,
                            String state, int maxObjects, long maxBytes, long expiresAt,
                            String manifestHash, String leaseToken, Long leaseUntil, String bucket, long ttlSeconds) { }
