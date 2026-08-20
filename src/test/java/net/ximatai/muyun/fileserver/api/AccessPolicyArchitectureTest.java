package net.ximatai.muyun.fileserver.api;

import jakarta.ws.rs.Path;
import net.ximatai.muyun.fileserver.common.security.PublicEndpoint;
import net.ximatai.muyun.fileserver.common.security.RequireIdentity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessPolicyArchitectureTest {

    @Test
    void everyJaxRsResourceDeclaresItsAccessPolicy() {
        try (Stream<java.nio.file.Path> files = Files.list(java.nio.file.Path.of("src/main/java/net/ximatai/muyun/fileserver/api"))) {
            files.filter(path -> path.getFileName().toString().endsWith("Resource.java"))
                    .map(this::resourceClass)
                    .forEach(this::assertAccessPolicy);
        } catch (IOException exception) {
            throw new AssertionError("could not enumerate JAX-RS resources", exception);
        }
    }

    private void assertAccessPolicy(Class<?> resource) {
            assertTrue(resource.isAnnotationPresent(Path.class), resource.getName() + " must be a JAX-RS resource");
            assertTrue(resource.isAnnotationPresent(PublicEndpoint.class)
                            || resource.isAnnotationPresent(RequireIdentity.class),
                    resource.getName() + " must declare @PublicEndpoint or @RequireIdentity");
    }

    private Class<?> resourceClass(java.nio.file.Path sourceFile) {
        String filename = sourceFile.getFileName().toString();
        String className = filename.substring(0, filename.length() - ".java".length());
        try {
            return Class.forName("net.ximatai.muyun.fileserver.api." + className);
        } catch (ClassNotFoundException exception) {
            throw new AssertionError("could not load resource class " + className, exception);
        }
    }
}
