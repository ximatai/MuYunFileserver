package net.ximatai.muyun.fileserver.common.context;

public record RequestContext(
        String tenantId,
        String userId,
        String requestId,
        String clientId
) {
}
