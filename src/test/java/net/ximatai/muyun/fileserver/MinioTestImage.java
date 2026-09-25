package net.ximatai.muyun.fileserver;

import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

/** Real MinIO built from pinned upstream source; shared by provider and HTTP contract tests. */
public final class MinioTestImage {
    private MinioTestImage() {}

    public static DockerImageName name() {
        String override = System.getenv("MFS_TEST_MINIO_IMAGE");
        String name = override == null || override.isBlank() ? Source.IMAGE.get() : override;
        return DockerImageName.parse(name).asCompatibleSubstituteFor("minio/minio");
    }

    private static final class Source {
        private static final ImageFromDockerfile IMAGE = new ImageFromDockerfile("muyun-test-minio:07c3a429bfed", false)
                .withFileFromClasspath("Dockerfile", "minio/Dockerfile");
    }
}
