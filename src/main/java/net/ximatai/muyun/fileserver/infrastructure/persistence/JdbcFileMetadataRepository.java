package net.ximatai.muyun.fileserver.infrastructure.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.domain.file.FileMetadata;
import net.ximatai.muyun.fileserver.domain.file.FileStatus;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class JdbcFileMetadataRepository implements FileMetadataRepository {

    @Inject
    DataSource dataSource;

    @Override
    public boolean existsById(String fileId) {
        String sql = "select 1 from file_metadata where id = ?";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, fileId);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to query file metadata existence", exception);
        }
    }

    @Override
    public void insert(FileMetadata metadata) {
        String sql = """
                insert into file_metadata (
                    id, tenant_id, original_filename, extension, mime_type, size_bytes, sha256,
                    storage_provider, storage_bucket, storage_key, status, uploaded_by, uploaded_at,
                    deleted_at, delete_marked_by, remark
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, metadata.id());
            statement.setString(2, metadata.tenantId());
            statement.setString(3, metadata.originalFilename());
            statement.setString(4, metadata.extension());
            statement.setString(5, metadata.mimeType());
            statement.setLong(6, metadata.sizeBytes());
            statement.setString(7, metadata.sha256());
            statement.setString(8, metadata.storageProvider());
            statement.setString(9, metadata.storageBucket());
            statement.setString(10, metadata.storageKey());
            statement.setString(11, metadata.status().name());
            statement.setString(12, metadata.uploadedBy());
            statement.setString(13, metadata.uploadedAt().toString());
            statement.setString(14, metadata.deletedAt() == null ? null : metadata.deletedAt().toString());
            statement.setString(15, metadata.deleteMarkedBy());
            statement.setString(16, metadata.remark());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to insert file metadata", exception);
        }
    }

    @Override
    public Optional<FileMetadata> findById(String fileId) {
        String sql = "select * from file_metadata where id = ?";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, fileId);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to query file metadata", exception);
        }
    }

    @Override
    public Optional<FileMetadata> findActiveById(String fileId) {
        String sql = "select * from file_metadata where id = ? and status = ?";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, fileId);
            statement.setString(2, FileStatus.ACTIVE.name());
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to query active file metadata", exception);
        }
    }

    @Override
    public boolean softDelete(String fileId, String tenantId, String deletedBy, Instant deletedAt) {
        String sql = """
                update file_metadata
                   set status = ?, deleted_at = ?, delete_marked_by = ?
                 where id = ? and tenant_id = ? and status = ?
                """;
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, FileStatus.DELETED.name());
            statement.setString(2, deletedAt.toString());
            statement.setString(3, deletedBy);
            statement.setString(4, fileId);
            statement.setString(5, tenantId);
            statement.setString(6, FileStatus.ACTIVE.name());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to soft delete file metadata", exception);
        }
    }

    @Override
    public List<FileMetadata> findDeletedBefore(Instant cutoff, int limit) {
        String sql = """
                select * from file_metadata
                 where status = ? and deleted_at is not null and deleted_at < ?
                 order by deleted_at asc
                 limit ?
                """;
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, FileStatus.DELETED.name());
            statement.setString(2, cutoff.toString());
            statement.setInt(3, limit);
            try (var resultSet = statement.executeQuery()) {
                List<FileMetadata> items = new ArrayList<>();
                while (resultSet.next()) {
                    items.add(map(resultSet));
                }
                return items;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to query deleted file metadata", exception);
        }
    }

    @Override
    public void deleteById(String fileId) {
        String sql = "delete from file_metadata where id = ?";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, fileId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to delete file metadata", exception);
        }
    }

    private FileMetadata map(ResultSet resultSet) throws SQLException {
        return new FileMetadata(
                resultSet.getString("id"),
                resultSet.getString("tenant_id"),
                resultSet.getString("original_filename"),
                resultSet.getString("extension"),
                resultSet.getString("mime_type"),
                resultSet.getLong("size_bytes"),
                resultSet.getString("sha256"),
                resultSet.getString("storage_provider"),
                resultSet.getString("storage_bucket"),
                resultSet.getString("storage_key"),
                FileStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("uploaded_by"),
                Instant.parse(resultSet.getString("uploaded_at")),
                parseInstant(resultSet.getString("deleted_at")),
                resultSet.getString("delete_marked_by"),
                resultSet.getString("remark")
        );
    }

    private Instant parseInstant(String value) {
        return value == null ? null : Instant.parse(value);
    }
}
