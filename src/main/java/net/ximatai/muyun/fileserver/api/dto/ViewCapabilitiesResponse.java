package net.ximatai.muyun.fileserver.api.dto;

public record ViewCapabilitiesResponse(
        boolean download,
        boolean zoom,
        boolean pageNavigate
) {
}
