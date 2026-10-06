package com.kratisai.controlplane.service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public interface BlobStorageService {

    void putObject(String path, byte[] content, String contentType);

    void putObject(String path, InputStream content, long length, String contentType);

    byte[] getObjectBytes(String path);

    InputStream getObject(String path);

    boolean exists(String path);

    void deleteObject(String path);

    default String getText(String path) {
        byte[] bytes = getObjectBytes(path);
        return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
    }

    default void putText(String path, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        putObject(path, bytes, "text/plain; charset=utf-8");
    }
}
