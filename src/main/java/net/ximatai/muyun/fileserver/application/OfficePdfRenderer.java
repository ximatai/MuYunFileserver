package net.ximatai.muyun.fileserver.application;

import java.nio.file.Path;

public interface OfficePdfRenderer {

    void verifyReadiness();

    RenderedPdfConversionResult convert(Path sourceFile, String originalFilename);
}
