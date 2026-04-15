package net.ximatai.muyun.fileserver.application;

import java.io.InputStream;

@FunctionalInterface
public interface RangeInputStreamSupplier {

    InputStream open(long start, long length);
}
