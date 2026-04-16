package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;
import net.ximatai.muyun.fileserver.domain.file.FileMetadata;

@ApplicationScoped
public class RenderedPdfPathResolver {

    public String storageKey(FileMetadata metadata) {
        return metadata.tenantId() + "/view-artifacts/" + metadata.id() + "/rendered.pdf";
    }

    public String renderedPdfFilename(FileMetadata metadata) {
        if ("application/pdf".equalsIgnoreCase(metadata.mimeType())) {
            return metadata.originalFilename();
        }
        int extensionIndex = metadata.originalFilename().lastIndexOf('.');
        String baseName = extensionIndex < 0
                ? metadata.originalFilename()
                : metadata.originalFilename().substring(0, extensionIndex);
        return baseName + ".pdf";
    }
}
