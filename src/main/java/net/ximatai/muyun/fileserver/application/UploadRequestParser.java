package net.ximatai.muyun.fileserver.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.common.exception.StorageCapacityException;
import net.ximatai.muyun.fileserver.common.exception.ValidationException;
import net.ximatai.muyun.fileserver.config.FileServiceConfig;
import org.jboss.resteasy.reactive.server.multipart.FormValue;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@ApplicationScoped
class UploadRequestParser {

    static final String FILE_IDS_NOT_SUPPORTED_FOR_TOKEN_UPLOAD = "file_ids is not supported for token upload";

    @Inject
    FileServiceConfig config;

    UploadRequest parse(MultipartFormDataInput input) {
        return parse(input, true);
    }

    UploadRequest parse(MultipartFormDataInput input, boolean allowRequestedFileIds) {
        Map<String, Collection<FormValue>> values = input.getValues();
        List<FormValue> fileValues = formValues(values, "files");
        List<String> requestedFileIds = textValues(values, "file_ids");
        String remark = singleOptionalText(values, "remark");

        validateRequest(fileValues, requestedFileIds, allowRequestedFileIds);
        return new UploadRequest(fileValues, requestedFileIds, remark);
    }

    private void validateRequest(List<FormValue> fileValues, List<String> requestedFileIds, boolean allowRequestedFileIds) {
        if (fileValues.isEmpty()) {
            throw new ValidationException("files is required");
        }
        if (fileValues.size() > config.upload().maxFileCount()) {
            throw new ValidationException("too many files in one request");
        }
        if (!allowRequestedFileIds && !requestedFileIds.isEmpty()) {
            throw new ValidationException(FILE_IDS_NOT_SUPPORTED_FOR_TOKEN_UPLOAD);
        }
        if (requestedFileIds.size() > fileValues.size()) {
            throw new ValidationException("file_ids count cannot exceed files count");
        }
        if (config.storage().tempDir().toFile().getUsableSpace() < config.upload().minFreeSpaceBytes()) {
            throw new StorageCapacityException("insufficient storage space");
        }
    }

    private List<FormValue> formValues(Map<String, Collection<FormValue>> values, String key) {
        Collection<FormValue> items = values.get(key);
        if (items == null) {
            return List.of();
        }
        return new ArrayList<>(items);
    }

    private List<String> textValues(Map<String, Collection<FormValue>> values, String key) {
        Collection<FormValue> items = values.get(key);
        if (items == null) {
            return List.of();
        }
        List<String> results = new ArrayList<>();
        for (FormValue item : items) {
            if (item.isFileItem()) {
                throw new ValidationException(key + " must be text field");
            }
            String value = item.getValue();
            if (value == null || value.isBlank()) {
                throw new ValidationException(key + " contains blank value");
            }
            results.add(value.trim());
        }
        return results;
    }

    private String singleOptionalText(Map<String, Collection<FormValue>> values, String key) {
        List<String> items = textValues(values, key);
        if (items.isEmpty()) {
            return null;
        }
        if (items.size() > 1) {
            throw new ValidationException(key + " must appear at most once");
        }
        return items.getFirst();
    }
}
