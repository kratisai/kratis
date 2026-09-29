package com.kratisai.controlplane.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Agent harness identity and definition. The set of harnesses is the {@code FILENAME.json} files
 * loaded from {@code classpath*:harnesses/} (and an optional overlay directory), not a closed enum.
 * A definition with {@code "enabled": false} is omitted from {@link #values()} but stays resolvable
 * via {@link #valueOf(String)} so persisted executions can still load.
 */
public final class AgentHarness {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ConcurrentHashMap<String, AgentHarness> HANDLES = new ConcurrentHashMap<>();
    private static final AtomicReference<Map<String, Definition>> DEFINITIONS = new AtomicReference<>(loadClasspath());

    private final String id;

    private AgentHarness(String id) {
        this.id = id;
    }

    private static AgentHarness intern(String id) {
        return HANDLES.computeIfAbsent(id, AgentHarness::new);
    }

    @JsonCreator
    public static AgentHarness valueOf(String id) {
        Objects.requireNonNull(id, "id");
        if (!DEFINITIONS.get().containsKey(id)) {
            throw new IllegalArgumentException("Unknown agent harness: " + id);
        }
        return intern(id);
    }

    public static AgentHarness[] values() {
        return DEFINITIONS.get().entrySet().stream()
                .filter(entry -> entry.getValue().enabled())
                .map(entry -> intern(entry.getKey()))
                .toArray(AgentHarness[]::new);
    }

    public static void overlayDirectory(String directory) {
        if (directory == null || directory.isBlank()) {
            return;
        }
        Path dir = Path.of(directory);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("Harness directory does not exist: " + dir.toAbsolutePath());
        }
        TreeMap<String, Definition> merged = new TreeMap<>(DEFINITIONS.get());
        try (Stream<Path> files = Files.list(dir)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                Path filename = path.getFileName();
                if (filename == null) {
                    throw new IllegalStateException("Harness path has no filename: " + path);
                }
                String name = filename.toString();
                if (!name.endsWith(".json")) {
                    continue;
                }
                merged.put(idFromFilename(name), readDefinition(path));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read harness directory " + dir.toAbsolutePath(), e);
        }
        DEFINITIONS.set(immutableSorted(merged));
    }

    @JsonValue
    public String name() {
        return id;
    }

    public String getName() {
        return definition().name();
    }

    public List<String> getSetupCommands() {
        return definition().setupCommands();
    }

    public List<HarnessResource> getResources() {
        return definition().resources();
    }

    public List<String> getAgentLogFiles() {
        return definition().agentLogFiles();
    }

    public String getAgentCommand() {
        return definition().agentCommand();
    }

    private Definition definition() {
        Definition definition = DEFINITIONS.get().get(id);
        if (definition == null) {
            throw new IllegalStateException("Unknown agent harness: " + id);
        }
        return definition;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof AgentHarness other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id;
    }

    private static Map<String, Definition> loadClasspath() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath*:harnesses/*.json");
            if (resources.length == 0) {
                throw new IllegalStateException("No harness definitions found on classpath:harnesses/*.json");
            }
            TreeMap<String, Definition> loaded = new TreeMap<>();
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) {
                    throw new IllegalStateException("Harness resource has no filename: " + resource);
                }
                String id = idFromFilename(filename);
                try (InputStream in = resource.getInputStream()) {
                    Definition previous = loaded.put(id, MAPPER.readValue(in, Definition.class));
                    if (previous != null) {
                        throw new IllegalStateException("Duplicate harness id " + id);
                    }
                }
            }
            return immutableSorted(loaded);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load harness definitions", e);
        }
    }

    private static Map<String, Definition> immutableSorted(TreeMap<String, Definition> definitions) {
        // Map.copyOf discards TreeMap key order; values() must stay alphabetical for UI lists.
        return Collections.unmodifiableMap(definitions);
    }

    private static Definition readDefinition(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return MAPPER.readValue(in, Definition.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read harness definition " + path, e);
        }
    }

    private static String idFromFilename(String filename) {
        if (!filename.endsWith(".json")) {
            throw new IllegalStateException("Harness filename must end with .json: " + filename);
        }
        String id = filename.substring(0, filename.length() - ".json".length());
        if (id.isBlank()) {
            throw new IllegalStateException("Harness filename has no id: " + filename);
        }
        return id;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Definition(
            String name,
            List<String> setupCommands,
            List<HarnessResource> resources,
            List<String> agentLogFiles,
            String agentCommand,
            Boolean enabled) {

        Definition {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(setupCommands, "setupCommands");
            Objects.requireNonNull(agentCommand, "agentCommand");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            if (setupCommands.isEmpty()) {
                throw new IllegalArgumentException("setupCommands must not be empty");
            }
            if (agentCommand.isBlank()) {
                throw new IllegalArgumentException("agentCommand must not be blank");
            }
            setupCommands = List.copyOf(setupCommands);
            resources = resources == null ? List.of() : List.copyOf(resources);
            if (agentLogFiles == null) {
                agentLogFiles = List.of();
            }
            if (agentLogFiles.stream().anyMatch(path -> path == null || path.isBlank())) {
                throw new IllegalArgumentException("agentLogFiles entries must not be blank");
            }
            agentLogFiles = List.copyOf(agentLogFiles);
            enabled = Objects.requireNonNullElse(enabled, Boolean.TRUE);
        }
    }
}
