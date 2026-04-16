package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.common.exception.GatewayTimeoutException;
import net.ximatai.muyun.fileserver.common.exception.ServiceUnavailableException;
import net.ximatai.muyun.fileserver.common.exception.UnprocessableEntityException;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;
import org.apache.tika.Tika;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class LibreOfficePdfRenderer implements OfficePdfRenderer {

    private static final String PDF_MIME_TYPE = "application/pdf";

    @Inject
    FileServiceConfig config;

    private final Tika tika = new Tika();
    private volatile Semaphore semaphore;

    @Override
    public void verifyReadiness() {
        resolveCommand(config.viewer().pdfRendering().libreoffice().command());
        ensureDirectory(config.viewer().pdfRendering().libreoffice().profileRoot());
    }

    @Override
    public RenderedPdfConversionResult convert(Path sourceFile, String originalFilename) {
        Objects.requireNonNull(sourceFile, "sourceFile");
        Objects.requireNonNull(originalFilename, "originalFilename");
        verifyReadiness();
        acquire();

        Path workDir = null;
        Path profileDir = null;
        try {
            workDir = Files.createTempDirectory(config.storage().tempDir(), "rendered-pdf-work-");
            profileDir = Files.createDirectories(config.viewer().pdfRendering().libreoffice().profileRoot()
                    .resolve("profile-" + Instant.now().toEpochMilli() + "-" + Thread.currentThread().threadId()));
            Path sourceCopy = workDir.resolve(sanitizeFilename(originalFilename));
            Files.copy(sourceFile, sourceCopy);

            Process process = new ProcessBuilder(
                    resolveCommand(config.viewer().pdfRendering().libreoffice().command()).toString(),
                    "--headless",
                    "-env:UserInstallation=" + profileDir.toUri(),
                    "--convert-to",
                    "pdf:writer_pdf_Export",
                    "--outdir",
                    workDir.toString(),
                    sourceCopy.toString()
            ).redirectErrorStream(true).start();

            boolean finished = process.waitFor(config.viewer().pdfRendering().libreoffice().timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new GatewayTimeoutException("pdf rendering timed out");
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new UnprocessableEntityException("pdf rendering failed: " + summarizeOutput(output));
            }

            Path pdfFile = workDir.resolve(toPdfFilename(sourceCopy.getFileName().toString()));
            if (!Files.exists(pdfFile) || Files.size(pdfFile) == 0L) {
                throw new UnprocessableEntityException("pdf rendering failed: generated pdf is missing");
            }

            String detectedMimeType = tika.detect(pdfFile);
            if (!PDF_MIME_TYPE.equalsIgnoreCase(detectedMimeType)) {
                throw new UnprocessableEntityException("pdf rendering failed: generated pdf is invalid");
            }

            return new RenderedPdfConversionResult(pdfFile, Files.size(pdfFile), sha256(pdfFile));
        } catch (ServiceUnavailableException | GatewayTimeoutException | UnprocessableEntityException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ServiceUnavailableException("pdf renderer is not available");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GatewayTimeoutException("pdf rendering timed out");
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new UnprocessableEntityException("pdf rendering failed");
        } finally {
            release();
            deleteDirectory(profileDir);
            // Keep work dir for returned output only on success.
            if (workDir != null && !containsRenderedPdfOutput(workDir)) {
                deleteDirectory(workDir);
            }
        }
    }

    private void acquire() {
        try {
            semaphore().acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GatewayTimeoutException("pdf rendering timed out");
        }
    }

    private void release() {
        semaphore().release();
    }

    private Semaphore semaphore() {
        if (semaphore == null) {
            synchronized (this) {
                if (semaphore == null) {
                    semaphore = new Semaphore(config.viewer().pdfRendering().libreoffice().maxConcurrency(), true);
                }
            }
        }
        return semaphore;
    }

    private Path resolveCommand(String configuredCommand) {
        Path directPath = Path.of(configuredCommand);
        if (configuredCommand.contains("/") || configuredCommand.contains("\\")) {
            if (Files.isExecutable(directPath)) {
                return directPath;
            }
            throw new ServiceUnavailableException("pdf renderer is not available");
        }

        String pathValue = System.getenv("PATH");
        if (pathValue == null || pathValue.isBlank()) {
            throw new ServiceUnavailableException("pdf renderer is not available");
        }
        for (String directory : pathValue.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(directory, configuredCommand);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        throw new ServiceUnavailableException("pdf renderer is not available");
    }

    private void ensureDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException exception) {
            throw new ServiceUnavailableException("pdf renderer is not available");
        }
    }

    private String sanitizeFilename(String originalFilename) {
        String sanitized = originalFilename.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return sanitized.isBlank() ? "document" : sanitized;
    }

    private String toPdfFilename(String originalFilename) {
        int extensionIndex = originalFilename.lastIndexOf('.');
        if (extensionIndex < 0) {
            return originalFilename + ".pdf";
        }
        return originalFilename.substring(0, extensionIndex) + ".pdf";
    }

    private String summarizeOutput(String output) {
        if (output == null || output.isBlank()) {
            return "unknown error";
        }
        String normalized = output.strip().replace('\n', ' ').replace('\r', ' ');
        return normalized.length() > 200 ? normalized.substring(0, 200) : normalized;
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(path));
        return HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
    }

    private boolean containsRenderedPdfOutput(Path workDir) {
        try (var stream = Files.list(workDir)) {
            return stream.anyMatch(path -> path.getFileName().toString().endsWith(".pdf"));
        } catch (IOException exception) {
            return false;
        }
    }

    private void deleteDirectory(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }
}
