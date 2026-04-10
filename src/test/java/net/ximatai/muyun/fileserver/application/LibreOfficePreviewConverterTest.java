package net.ximatai.muyun.fileserver.application;

import net.ximatai.muyun.fileserver.common.exception.GatewayTimeoutException;
import net.ximatai.muyun.fileserver.common.exception.ServiceUnavailableException;
import net.ximatai.muyun.fileserver.common.exception.UnprocessableEntityException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibreOfficePreviewConverterTest {

    @Test
    void shouldConvertWithExecutableCommand() throws Exception {
        Path script = createScript("""
                #!/bin/sh
                set -eu
                OUTDIR=""
                INPUT=""
                while [ "$#" -gt 0 ]; do
                  case "$1" in
                    --outdir) OUTDIR="$2"; shift 2 ;;
                    --convert-to) shift 2 ;;
                    --headless) shift 1 ;;
                    -env:UserInstallation=*) shift 1 ;;
                    *) INPUT="$1"; shift 1 ;;
                  esac
                done
                TARGET="$OUTDIR/$(basename "$INPUT" .docx).pdf"
                cat > "$TARGET" <<'EOF'
                %PDF-1.4
                1 0 obj
                << /Type /Catalog /Pages 2 0 R >>
                endobj
                2 0 obj
                << /Type /Pages /Count 1 /Kids [3 0 R] >>
                endobj
                3 0 obj
                << /Type /Page /Parent 2 0 R /MediaBox [0 0 300 144] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>
                endobj
                4 0 obj
                << /Length 37 >>
                stream
                BT /F1 18 Tf 72 96 Td (Unit PDF) Tj ET
                endstream
                endobj
                5 0 obj
                << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>
                endobj
                xref
                0 6
                0000000000 65535 f 
                0000000009 00000 n 
                0000000058 00000 n 
                0000000115 00000 n 
                0000000241 00000 n 
                0000000328 00000 n 
                trailer
                << /Size 6 /Root 1 0 R >>
                startxref
                398
                %%EOF
                EOF
                """);
        LibreOfficePreviewConverter converter = new LibreOfficePreviewConverter();
        converter.config = TestConfigs.fileServiceConfigWithPreviewCommand(script.toString());

        Path source = Files.createTempFile("preview-converter", ".docx");
        Files.writeString(source, "fake");

        PreviewConversionResult result = converter.convert(source, "contract.docx");

        assertTrue(Files.exists(result.outputFile()));
        assertEquals("application/pdf", new org.apache.tika.Tika().detect(result.outputFile()));
    }

    @Test
    void shouldThrowServiceUnavailableWhenCommandIsMissing() throws Exception {
        LibreOfficePreviewConverter converter = new LibreOfficePreviewConverter();
        converter.config = TestConfigs.fileServiceConfigWithPreviewCommand("missing-soffice-command");

        Path source = Files.createTempFile("preview-converter", ".docx");
        Files.writeString(source, "fake");

        assertThrows(ServiceUnavailableException.class, () -> converter.convert(source, "contract.docx"));
    }

    @Test
    void shouldThrowTimeoutWhenCommandHangs() throws Exception {
        Path script = createScript("""
                #!/bin/sh
                sleep 10
                """);
        LibreOfficePreviewConverter converter = new LibreOfficePreviewConverter();
        converter.config = TestConfigs.fileServiceConfigWithPreviewCommand(script.toString());

        Path source = Files.createTempFile("preview-converter", ".docx");
        Files.writeString(source, "fake");

        assertThrows(GatewayTimeoutException.class, () -> converter.convert(source, "contract.docx"));
    }

    @Test
    void shouldThrowUnprocessableWhenOutputIsMissing() throws Exception {
        Path script = createScript("""
                #!/bin/sh
                exit 0
                """);
        LibreOfficePreviewConverter converter = new LibreOfficePreviewConverter();
        converter.config = TestConfigs.fileServiceConfigWithPreviewCommand(script.toString());

        Path source = Files.createTempFile("preview-converter", ".docx");
        Files.writeString(source, "fake");

        assertThrows(UnprocessableEntityException.class, () -> converter.convert(source, "contract.docx"));
    }

    private Path createScript(String content) throws Exception {
        Path script = Files.createTempFile("fake-soffice-unit", ".sh");
        Files.writeString(script, content);
        script.toFile().setExecutable(true);
        return script;
    }
}
