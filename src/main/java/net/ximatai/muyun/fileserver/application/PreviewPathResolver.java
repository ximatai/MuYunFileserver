package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;
import net.ximatai.muyun.fileserver.domain.file.FileMetadata;

@ApplicationScoped
public class PreviewPathResolver {

    public String storageKey(FileMetadata metadata) {
        return metadata.tenantId() + "/previews/" + metadata.id() + "/preview.pdf";
    }

    public String previewFilename(FileMetadata metadata) {
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
