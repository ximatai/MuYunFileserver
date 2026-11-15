package net.ximatai.muyun.fileserver;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(value = MinioReceptionTestResource.class, restrictToAnnotatedClass = true)
class MinioReceptionResourceTest extends S3ReceptionContract { }
