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

/**
 * Agent harness identity and definition. The catalogue is the {@code FILENAME.json} files in the
 * directory named by {@code kratis.harnesses.directory}; deliberately not a classpath scan, which a
 * native image cannot enumerate.
 *
 * <p>A definition with {@code "enabled": false} is omitted from {@link #values()} but stays
 * resolvable via {@link #valueOf(String)} so persisted executions can still load.
 */
public final class AgentHarness {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ConcurrentHashMap<String, AgentHarness> HANDLES = new ConcurrentHashMap<>();
    private static final AtomicReference<Map<String, Definition>> DEFINITIONS = new AtomicReference<>();

    private final String id;

    private AgentHarness(String id) {
        this.id = id;
    }

    private static AgentHarness intern(String id) {
        return HANDLES.computeIfAbsent(id, AgentHarness::new);
    }

    public static void load(Path directory) {
        DEFINITIONS.set(readDirectory(directory));
    }

    @JsonCreator
    public static AgentHarness valueOf(String id) {
        Objects.requireNonNull(id, "id");
        if (!catalog().containsKey(id)) {
            throw new IllegalArgumentException("Unknown agent harness: " + id);
        }
        return intern(id);
    }

    public static AgentHarness[] values() {
        return catalog().entrySet().stream()
                .filter(entry -> entry.getValue().enabled())
                .map(entry -> intern(entry.getKey()))
                .toArray(AgentHarness[]::new);
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
        Definition definition = catalog().get(id);
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

    private static Map<String, Definition> catalog() {
        Map<String, Definition> definitions = DEFINITIONS.get();
        if (definitions == null) {
            throw new IllegalStateException("Agent harness catalogue is not loaded; set "
                    + "kratis.harnesses.directory and let HarnessCatalogInitializer run first");
        }
        return definitions;
    }

    static Map<String, Definition> readDirectory(Path directory) {
        Objects.requireNonNull(directory, "directory");
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("Harness directory does not exist: " + directory.toAbsolutePath());
        }
        TreeMap<String, Definition> loaded = new TreeMap<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                String filename = Objects.requireNonNull(path.getFileName(), "harness path has no filename")
                        .toString();
                if (!filename.endsWith(".json")) {
                    continue;
                }
                String id = filename.substring(0, filename.length() - ".json".length());
                if (id.isBlank()) {
                    throw new IllegalStateException("Harness filename has no id: " + filename);
                }
                loaded.put(id, readDefinition(path));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read harness directory " + directory.toAbsolutePath(), e);
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("No harness definitions (*.json) in " + directory.toAbsolutePath());
        }
        return immutableSorted(loaded);
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Definition(
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
