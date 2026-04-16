package net.ximatai.muyun.fileserver.infrastructure.persistence;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.domain.view.FileViewArtifact;
import net.ximatai.muyun.fileserver.domain.view.ViewArtifactFailureCode;
import net.ximatai.muyun.fileserver.domain.view.ViewArtifactSourceKind;
import net.ximatai.muyun.fileserver.domain.view.ViewArtifactStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class FileViewArtifactRepositoryTest {

    private static final String ARTIFACT_KEY = "view_pdf";

    @Inject
    FileViewArtifactRepository repository;

    @Test
    void shouldSaveFindTouchAndDeleteArtifact() {
        Instant now = Instant.now();
        String fileId = "01KNV9ZZZZZZZZZZZZZZZZZZZZ";
        FileViewArtifact artifact = new FileViewArtifact(
                fileId,
                ARTIFACT_KEY,
                "tenant-a",
                ViewArtifactSourceKind.GENERATED_PDF,
                ViewArtifactStatus.READY,
                "application/pdf",
                "local",
                null,
                "tenant-a/view-artifacts/" + fileId + "/rendered.pdf",
                123L,
                "abc",
                now,
                now,
                null,
                null
        );

        repository.save(artifact);

        FileViewArtifact saved = repository.findByFileIdAndArtifactKey(fileId, ARTIFACT_KEY).orElseThrow();
        assertEquals(ViewArtifactStatus.READY, saved.status());
        assertEquals(123L, saved.sizeBytes());

        Instant touchedAt = now.plusSeconds(5);
        repository.touchAccessedAt(fileId, ARTIFACT_KEY, touchedAt);
        assertEquals(touchedAt, repository.findByFileIdAndArtifactKey(fileId, ARTIFACT_KEY).orElseThrow().lastAccessedAt());

        repository.save(new FileViewArtifact(
                fileId,
                ARTIFACT_KEY,
                "tenant-a",
                ViewArtifactSourceKind.GENERATED_PDF,
                ViewArtifactStatus.FAILED,
                "application/pdf",
                null,
                null,
                null,
                null,
                null,
                touchedAt,
                touchedAt,
                ViewArtifactFailureCode.CONVERSION_FAILED,
                "failed"
        ));
        FileViewArtifact updated = repository.findByFileIdAndArtifactKey(fileId, ARTIFACT_KEY).orElseThrow();
        assertEquals(ViewArtifactStatus.FAILED, updated.status());
        assertEquals(ViewArtifactFailureCode.CONVERSION_FAILED, updated.failureCode());

        repository.deleteByFileId(fileId);
        assertTrue(repository.findByFileIdAndArtifactKey(fileId, ARTIFACT_KEY).isEmpty());
    }
}
