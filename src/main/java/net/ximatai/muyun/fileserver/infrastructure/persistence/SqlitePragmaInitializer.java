package net.ximatai.muyun.fileserver.infrastructure.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.runtime.StartupEvent;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;

import java.io.IOException;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.file.Files;
import java.nio.file.Path;

@ApplicationScoped
public class SqlitePragmaInitializer {

    @Inject
    DataSource dataSource;

    @Inject
    FileServiceConfig config;

    public void onStart(@Observes StartupEvent ignored) {
        createDatabaseDirectory();
        try (var connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA busy_timeout = 5000");
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to initialize sqlite pragmas", exception);
        }
    }

    private void createDatabaseDirectory() {
        Path parent = config.database().path().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to initialize database directory", exception);
        }
    }
}
