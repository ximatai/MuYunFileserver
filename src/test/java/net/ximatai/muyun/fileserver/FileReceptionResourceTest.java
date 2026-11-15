package net.ximatai.muyun.fileserver;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(value = SeaweedFileResourceTestResource.class, restrictToAnnotatedClass = true)
class FileReceptionResourceTest extends S3ReceptionContract { }
