package net.ximatai.muyun.fileserver.application;

import org.jboss.resteasy.reactive.server.multipart.FormValue;

import java.util.List;

record UploadRequest(
        List<FormValue> fileValues,
        List<String> requestedFileIds,
        String remark
) {
}
