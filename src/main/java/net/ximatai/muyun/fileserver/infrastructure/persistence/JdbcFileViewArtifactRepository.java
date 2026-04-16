package net.ximatai.muyun.fileserver.infrastructure.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.domain.view.FileViewArtifact;
import net.ximatai.muyun.fileserver.domain.view.ViewArtifactFailureCode;
import net.ximatai.muyun.fileserver.domain.view.ViewArtifactSourceKind;
import net.ximatai.muyun.fileserver.domain.view.ViewArtifactStatus;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
public class JdbcFileViewArtifactRepository implements FileViewArtifactRepository {

    @Inject
    DataSource dataSource;

    @Override
    public Optional<FileViewArtifact> findByFileIdAndArtifactKey(String fileId, String artifactKey) {
        String sql = "select * from file_preview_artifact where file_id = ? and artifact_key = ?";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, fileId);
            statement.setString(2, artifactKey);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to query file view artifact", exception);
        }
    }

    @Override
    public void save(FileViewArtifact artifact) {
        String sql = """
                insert into file_preview_artifact (
                    file_id, artifact_key, tenant_id, source_kind, status, target_mime_type, storage_provider,
                    storage_bucket, storage_key, size_bytes, sha256, generated_at, last_accessed_at,
                    failure_code, failure_message
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(file_id, artifact_key) do update set
                    tenant_id = excluded.tenant_id,
                    source_kind = excluded.source_kind,
                    status = excluded.status,
                    target_mime_type = excluded.target_mime_type,
                    storage_provider = excluded.storage_provider,
                    storage_bucket = excluded.storage_bucket,
                    storage_key = excluded.storage_key,
                    size_bytes = excluded.size_bytes,
                    sha256 = excluded.sha256,
                    generated_at = excluded.generated_at,
                    last_accessed_at = excluded.last_accessed_at,
                    failure_code = excluded.failure_code,
                    failure_message = excluded.failure_message
                """;
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, artifact.fileId());
            statement.setString(2, artifact.artifactKey());
            statement.setString(3, artifact.tenantId());
            statement.setString(4, artifact.sourceKind().name());
            statement.setString(5, artifact.status().name());
            statement.setString(6, artifact.targetMimeType());
            statement.setString(7, artifact.storageProvider());
            statement.setString(8, artifact.storageBucket());
            statement.setString(9, artifact.storageKey());
            if (artifact.sizeBytes() == null) {
                statement.setObject(10, null);
            } else {
                statement.setLong(10, artifact.sizeBytes());
            }
            statement.setString(11, artifact.sha256());
            statement.setString(12, artifact.generatedAt().toString());
            statement.setString(13, artifact.lastAccessedAt().toString());
            statement.setString(14, artifact.failureCode() == null ? null : artifact.failureCode().name());
            statement.setString(15, artifact.failureMessage());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to save file view artifact", exception);
        }
    }

    @Override
    public void touchAccessedAt(String fileId, String artifactKey, Instant accessedAt) {
        String sql = "update file_preview_artifact set last_accessed_at = ? where file_id = ? and artifact_key = ?";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, accessedAt.toString());
            statement.setString(2, fileId);
            statement.setString(3, artifactKey);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to update file view artifact access time", exception);
        }
    }

    @Override
    public void deleteByFileId(String fileId) {
        String sql = "delete from file_preview_artifact where file_id = ?";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, fileId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to delete file view artifact", exception);
        }
    }

    private FileViewArtifact map(ResultSet resultSet) throws SQLException {
        return new FileViewArtifact(
                resultSet.getString("file_id"),
                resultSet.getString("artifact_key"),
                resultSet.getString("tenant_id"),
                ViewArtifactSourceKind.valueOf(resultSet.getString("source_kind")),
                ViewArtifactStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("target_mime_type"),
                resultSet.getString("storage_provider"),
                resultSet.getString("storage_bucket"),
                resultSet.getString("storage_key"),
                resultSet.getObject("size_bytes") == null ? null : resultSet.getLong("size_bytes"),
                resultSet.getString("sha256"),
                Instant.parse(resultSet.getString("generated_at")),
                Instant.parse(resultSet.getString("last_accessed_at")),
                parseFailureCode(resultSet.getString("failure_code")),
                resultSet.getString("failure_message")
        );
    }

    private ViewArtifactFailureCode parseFailureCode(String value) {
        return value == null || value.isBlank() ? null : ViewArtifactFailureCode.valueOf(value);
    }
}
