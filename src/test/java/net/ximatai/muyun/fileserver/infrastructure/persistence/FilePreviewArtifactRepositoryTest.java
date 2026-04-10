package net.ximatai.muyun.fileserver.infrastructure.persistence;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.domain.preview.FilePreviewArtifact;
import net.ximatai.muyun.fileserver.domain.preview.PreviewArtifactStatus;
import net.ximatai.muyun.fileserver.domain.preview.PreviewFailureCode;
import net.ximatai.muyun.fileserver.domain.preview.PreviewSourceKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class FilePreviewArtifactRepositoryTest {

    private static final String ARTIFACT_KEY = "preview_pdf";

    @Inject
    FilePreviewArtifactRepository repository;

    @Test
    void shouldSaveFindTouchAndDeleteArtifact() {
        Instant now = Instant.now();
        String fileId = "01KNV9ZZZZZZZZZZZZZZZZZZZZ";
        FilePreviewArtifact artifact = new FilePreviewArtifact(
                fileId,
                ARTIFACT_KEY,
                "tenant-a",
                PreviewSourceKind.GENERATED_PDF,
                PreviewArtifactStatus.READY,
                "application/pdf",
                "local",
                null,
                "tenant-a/previews/" + fileId + "/preview.pdf",
                123L,
                "abc",
                now,
                now,
                null,
                null
        );

        repository.save(artifact);

        FilePreviewArtifact saved = repository.findByFileIdAndArtifactKey(fileId, ARTIFACT_KEY).orElseThrow();
        assertEquals(PreviewArtifactStatus.READY, saved.status());
        assertEquals(123L, saved.sizeBytes());

        Instant touchedAt = now.plusSeconds(5);
        repository.touchAccessedAt(fileId, ARTIFACT_KEY, touchedAt);
        assertEquals(touchedAt, repository.findByFileIdAndArtifactKey(fileId, ARTIFACT_KEY).orElseThrow().lastAccessedAt());

        repository.save(new FilePreviewArtifact(
                fileId,
                ARTIFACT_KEY,
                "tenant-a",
                PreviewSourceKind.GENERATED_PDF,
                PreviewArtifactStatus.FAILED,
                "application/pdf",
                null,
                null,
                null,
                null,
                null,
                touchedAt,
                touchedAt,
                PreviewFailureCode.CONVERSION_FAILED,
                "failed"
        ));
        FilePreviewArtifact updated = repository.findByFileIdAndArtifactKey(fileId, ARTIFACT_KEY).orElseThrow();
        assertEquals(PreviewArtifactStatus.FAILED, updated.status());
        assertEquals(PreviewFailureCode.CONVERSION_FAILED, updated.failureCode());

        repository.deleteByFileId(fileId);
        assertTrue(repository.findByFileIdAndArtifactKey(fileId, ARTIFACT_KEY).isEmpty());
    }
}
