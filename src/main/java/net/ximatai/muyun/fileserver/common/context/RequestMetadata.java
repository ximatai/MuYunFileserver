package net.ximatai.muyun.fileserver.common.context;

/**
 * Request data that is safe to collect before JAX-RS has matched a resource.
 */
public record RequestMetadata(String method, String path, String traceId) {
}
