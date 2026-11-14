package com.kratisai.controlplane.ingestion.parse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class CmmLanguage {
    private static final String RESOURCE_PATH = "/cmm-languages.txt";
    private static final List<String> NAMES = loadNames();

    private CmmLanguage() {}

    public static String name(int id) {
        return id >= 0 && id < NAMES.size() ? NAMES.get(id) : null;
    }

    private static List<String> loadNames() {
        try (InputStream input = CmmLanguage.class.getResourceAsStream(RESOURCE_PATH)) {
            if (input == null) {
                throw new IllegalStateException("Missing classpath resource: " + RESOURCE_PATH);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                return reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .toList();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load CMM languages from " + RESOURCE_PATH, e);
        }
    }
}
