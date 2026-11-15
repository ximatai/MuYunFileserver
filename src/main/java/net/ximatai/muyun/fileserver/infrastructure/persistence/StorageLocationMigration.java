package net.ximatai.muyun.fileserver.infrastructure.persistence;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.infrastructure.storage.StorageLocations;
import net.ximatai.muyun.fileserver.infrastructure.storage.StorageLocations.Role;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@ApplicationScoped
public class StorageLocationMigration {
    @Inject DataSource source;
    @Inject StorageLocations locations;

    // Resolve recorded locations before bucket creation or workspace cleanup.
    void initialize(@Observes @Priority(1800) StartupEvent ignored) {
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                pinBindings(connection);
                bindLegacyRows(connection);
                validateReferences(connection, "file_metadata", "1=1", Role.ASSET);
                validateReferences(connection, "file_preview_artifact", "storage_key is not null", Role.ASSET);
                validateReferences(connection, "file_reception", "1=1", Role.RECEPTION);
                validateReferences(connection, "file_reception_staged", "1=1", Role.ASSET);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to bind storage locations", e);
        }
    }

    private void pinBindings(Connection connection) throws SQLException {
        try (var insert = connection.prepareStatement("""
                insert or ignore into storage_location_binding(location_id, namespace, role) values (?, ?, ?)
                """);
             var select = connection.prepareStatement("""
                select namespace, role from storage_location_binding where location_id=?
                """)) {
            for (var binding : locations.identities()) {
                insert.setString(1, binding.id());
                insert.setString(2, binding.namespace());
                insert.setString(3, binding.role().name());
                insert.executeUpdate();
                select.setString(1, binding.id());
                try (var rows = select.executeQuery()) {
                    if (!rows.next() || !binding.namespace().equals(rows.getString(1))
                            || !binding.role().name().equals(rows.getString(2))) {
                        throw new IllegalStateException("storage location was rebound: " + binding.id()
                                + "; restore its original configuration or use a new location ID");
                    }
                }
            }
        }
    }

    private void bindLegacyRows(Connection connection) throws SQLException {
        if (locations.legacyAssets() != null) {
            var assets = locations.require(locations.legacyAssets(), Role.ASSET);
            String oldType = "s3".equals(assets.type()) ? "minio" : assets.type();
            for (String table : new String[] {"file_metadata", "file_preview_artifact"}) {
                update(connection, "update " + table + """
                         set storage_location_id=? where storage_location_id is null
                         and storage_key is not null and storage_provider=? and storage_bucket is ?
                        """, assets.id(), oldType, assets.bucket());
            }
            update(connection, """
                    update file_reception_staged set storage_location_id=? where storage_location_id is null
                    """, assets.id());
        }
        if (locations.legacyReception() != null) {
            var receptions = locations.require(locations.legacyReception(), Role.RECEPTION);
            update(connection, """
                    update file_reception set storage_location_id=? where storage_location_id is null and bucket=?
                    """, receptions.id(), receptions.bucket());
        }
    }

    private void validateReferences(Connection connection, String table, String hasObject, Role role) throws SQLException {
        String sql = "select distinct storage_location_id from " + table + " where " + hasObject;
        try (var query = connection.prepareStatement(sql); var rows = query.executeQuery()) {
            while (rows.next()) {
                String id = rows.getString(1);
                if (id == null) {
                    throw new IllegalStateException("unresolved legacy storage location in " + table
                            + "; configure the actual legacy location before starting");
                }
                locations.require(id, role);
            }
        }
    }

    private void update(Connection connection, String sql, String... args) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setString(index + 1, args[index]);
            }
            statement.executeUpdate();
        }
    }
}
