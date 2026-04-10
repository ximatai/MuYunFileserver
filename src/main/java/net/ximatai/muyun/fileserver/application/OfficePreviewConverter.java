package net.ximatai.muyun.fileserver.application;

import java.nio.file.Path;

public interface OfficePreviewConverter {

    void verifyReadiness();

    PreviewConversionResult convert(Path sourceFile, String originalFilename);
}
