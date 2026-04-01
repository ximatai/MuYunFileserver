package net.ximatai.muyun.fileserver.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.sql.Connection;

@Readiness
@ApplicationScoped
public class StorageReadinessCheck implements HealthCheck {

    @Inject
    DataSource dataSource;

    @Inject
    FileServiceConfig config;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("storage-readiness");

        try (Connection connection = dataSource.getConnection()) {
            connection.prepareStatement("select 1").execute();
        } catch (Exception exception) {
            return builder.down()
                    .withData("database", exception.getMessage())
                    .build();
        }

        if (!Files.isDirectory(config.storage().rootDir()) || !Files.isWritable(config.storage().rootDir())) {
            return builder.down()
                    .withData("storageRoot", config.storage().rootDir().toString())
                    .build();
        }

        if (!Files.isDirectory(config.storage().tempDir()) || !Files.isWritable(config.storage().tempDir())) {
            return builder.down()
                    .withData("tempDir", config.storage().tempDir().toString())
                    .build();
        }

        return builder.up().build();
    }
}
