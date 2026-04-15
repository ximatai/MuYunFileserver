package net.ximatai.muyun.fileserver.application;

import java.io.InputStream;

@FunctionalInterface
public interface InputStreamSupplier {

    InputStream open();
}
