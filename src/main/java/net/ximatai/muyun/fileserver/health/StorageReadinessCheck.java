package net.ximatai.muyun.fileserver.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;
import net.ximatai.muyun.fileserver.common.exception.StorageException;
import net.ximatai.muyun.fileserver.infrastructure.storage.StorageProvider;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import javax.sql.DataSource;
import java.sql.Connection;

@Readiness
@ApplicationScoped
public class StorageReadinessCheck implements HealthCheck {

    @Inject
    DataSource dataSource;

    @Inject
    FileServiceConfig config;

    @Inject
    StorageProvider storageProvider;

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

        try {
            storageProvider.verifyReadiness();
        } catch (StorageException exception) {
            return builder.down()
                    .withData("storageType", config.storage().type())
                    .withData("storage", exception.getMessage())
                    .build();
        }

        return builder.up().build();
    }
}
