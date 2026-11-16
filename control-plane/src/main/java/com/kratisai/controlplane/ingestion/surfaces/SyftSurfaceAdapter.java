package com.kratisai.controlplane.ingestion.surfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.config.IngestionProperties;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
import com.kratisai.controlplane.model.AttributeKind;
import com.kratisai.controlplane.model.CtxSurface;
import com.kratisai.controlplane.model.CtxSurfaceAttribute;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.SurfaceDetection;
import com.kratisai.controlplane.model.SurfaceRole;
import com.kratisai.controlplane.service.ProcessExecutor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
class SyftSurfaceAdapter {
    private static final String UNATTRIBUTED = "(unattributed)";

    private record DependencyGraph(Set<String> roots, Set<String> directRefs) {}

    private final ProcessExecutor processExecutor;
    private final IngestionProperties ingestionProperties;
    private final ObjectMapper objectMapper;

    SyftSurfaceAdapter(
            ProcessExecutor processExecutor, IngestionProperties ingestionProperties, ObjectMapper objectMapper) {
        this.processExecutor = processExecutor;
        this.ingestionProperties = ingestionProperties;
        this.objectMapper = objectMapper;
    }

    List<CtxSurface> extract(IngestionBatch batch, Path root, IngestionBatchLogService.BatchLogger batchLogger) {
        Path document;
        try {
            document = Files.createTempFile("kratis-syft-", ".json");
        } catch (IOException e) {
            throw new IllegalStateException("Could not create Syft output file: " + e.getMessage(), e);
        }
        try {
            String binary = Path.of(ingestionProperties.getSbom().getSyftPath())
                    .toAbsolutePath()
                    .normalize()
                    .toString();
            List<String> command =
                    List.of(binary, "dir:" + root.toAbsolutePath(), "-o", "cyclonedx-json=" + document, "-q");
            ProcessExecutor.ProcessResult result =
                    processExecutor.execute(command, root.toFile(), Map.of("SYFT_CHECK_FOR_APP_UPDATE", "false"));
            if (result.exitCode() != 0) {
                throw new IllegalStateException("Syft failed with exit " + result.exitCode() + " for " + root + ": "
                        + new String(result.output(), StandardCharsets.UTF_8).strip());
            }
            return parse(batch, objectMapper.readTree(document.toFile()), batchLogger);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not run Syft at " + ingestionProperties.getSbom().getSyftPath() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Syft run interrupted for " + root, e);
        } finally {
            try {
                Files.deleteIfExists(document);
            } catch (IOException ignored) {
            }
        }
    }

    List<CtxSurface> parse(IngestionBatch batch, JsonNode bom, IngestionBatchLogService.BatchLogger batchLogger) {
        JsonNode components = bom.path("components");
        JsonNode metadataComponent = bom.path("metadata").path("component");
        if (components.isEmpty() && text(metadataComponent, "purl") == null) {
            debug(batchLogger, "SBOM produced 0 components.");
            return List.of();
        }
        Map<String, JsonNode> componentsByRef = new HashMap<>();
        for (JsonNode component : components) {
            String ref = text(component, "bom-ref");
            if (ref != null) {
                componentsByRef.put(ref, component);
            }
        }
        String metadataRef = text(metadataComponent, "bom-ref");
        if (metadataRef != null) {
            componentsByRef.putIfAbsent(metadataRef, metadataComponent);
        }
        DependencyGraph dependencyGraph = dependencyGraph(bom);
        Set<String> rootRefs = new LinkedHashSet<>(dependencyGraph.roots());
        if (metadataRef != null && text(metadataComponent, "purl") != null) {
            rootRefs.add(metadataRef);
        }
        List<CtxSurface> result = new ArrayList<>();
        Set<String> emittedPackages = new LinkedHashSet<>();
        for (String ref : rootRefs) {
            JsonNode component = componentsByRef.get(ref);
            if (component == null || !accepted(component)) {
                continue;
            }
            String purl = text(component, "purl");
            String path = manifestPath(component);
            if (purl != null && emittedPackages.add(path + "|" + purl)) {
                result.add(providedPackage(batch, path, component));
            }
        }
        Map<String, List<JsonNode>> byManifest = new LinkedHashMap<>();
        for (JsonNode component : components) {
            if (rootRefs.contains(text(component, "bom-ref")) || !accepted(component)) {
                continue;
            }
            String path = manifestPath(component);
            byManifest.computeIfAbsent(path, unused -> new ArrayList<>()).add(component);
        }
        for (Map.Entry<String, List<JsonNode>> entry : byManifest.entrySet()) {
            String path = entry.getKey();
            List<JsonNode> candidates = entry.getValue();
            boolean graphPresent = !dependencyGraph.roots().isEmpty();
            boolean groupHasGraph = graphPresent
                    && candidates.stream()
                            .anyMatch(component -> dependencyGraph.directRefs().contains(text(component, "bom-ref")));
            List<JsonNode> included = candidates;
            if (groupHasGraph) {
                included = candidates.stream()
                        .filter(component -> dependencyGraph.directRefs().contains(text(component, "bom-ref")))
                        .toList();
            }
            int transitive = candidates.size() - included.size();
            if (included.isEmpty()) {
                debug(
                        batchLogger,
                        "Manifest " + path + " skipped: 0 direct dependencies (" + transitive + " transitive omitted)");
                continue;
            }
            result.add(surface(batch, path, included, transitive));
        }
        if (result.isEmpty()) {
            debug(batchLogger, "SBOM produced 0 package surfaces (components=" + components.size() + ").");
        }
        return result;
    }

    private static void debug(IngestionBatchLogService.BatchLogger batchLogger, String message) {
        if (batchLogger != null) {
            batchLogger.debug("EXTRACT_SURFACES", message);
        }
    }

    private DependencyGraph dependencyGraph(JsonNode bom) {
        JsonNode dependencies = bom.path("dependencies");
        if (!dependencies.isArray() || dependencies.isEmpty()) {
            return new DependencyGraph(Set.of(), Set.of());
        }
        Map<String, List<String>> dependsOn = new LinkedHashMap<>();
        Map<String, Integer> incoming = new HashMap<>();
        for (JsonNode dependency : dependencies) {
            String ref = text(dependency, "ref");
            if (ref == null) {
                continue;
            }
            List<String> children = new ArrayList<>();
            for (JsonNode child : dependency.path("dependsOn")) {
                String value = child.asText(null);
                if (value != null && !value.isBlank()) {
                    children.add(value);
                    incoming.merge(value, 1, Integer::sum);
                }
            }
            if (!children.isEmpty()) {
                dependsOn.put(ref, children);
            }
        }
        List<String> roots = dependsOn.keySet().stream()
                .filter(s -> incoming.getOrDefault(s, 0) == 0)
                .toList();
        Set<String> direct = new LinkedHashSet<>();
        for (String root : roots) {
            direct.addAll(dependsOn.getOrDefault(root, List.of()));
        }
        return new DependencyGraph(new LinkedHashSet<>(roots), direct);
    }

    private static boolean accepted(JsonNode component) {
        String scope = property(component, "syft:package:scope");
        if (scope == null) scope = property(component, "syft:scope");
        if (scope == null) scope = text(component, "scope");
        if (testScope(scope)) {
            return false;
        }
        return text(component, "purl") != null;
    }

    private static CtxSurface providedPackage(IngestionBatch batch, String path, JsonNode component) {
        String purl = text(component, "purl");
        String technology = purlType(purl);
        String description = "Package identity declared by " + path;
        CtxSurface surface = new CtxSurface(
                batch,
                batch.getRepository().getId(),
                batch.getRepository().getTeam().getId(),
                SurfaceRole.PROVIDES,
                description,
                SurfaceDetection.SYFT);
        add(surface, AttributeKind.IDENTIFIER, text(component, "name"));
        add(surface, AttributeKind.TECHNOLOGY, technology);
        add(surface, AttributeKind.IDENTIFIER, purl);
        if (!UNATTRIBUTED.equals(path)) {
            add(surface, AttributeKind.FILE, path);
        }
        return surface;
    }

    private static CtxSurface surface(IngestionBatch batch, String path, List<JsonNode> components, int transitive) {
        Set<String> technologies = new LinkedHashSet<>();
        Set<String> purls = new LinkedHashSet<>();
        for (JsonNode component : components) {
            String purl = text(component, "purl");
            purls.add(purl);
            String technology = purlType(purl);
            if (technology != null) {
                technologies.add(technology);
            }
        }
        String description = technologies.isEmpty()
                ? "Dependency manifest " + path
                : String.join(", ", technologies) + " dependency manifest " + path;
        CtxSurface surface = new CtxSurface(
                batch,
                batch.getRepository().getId(),
                batch.getRepository().getTeam().getId(),
                SurfaceRole.REQUIRES,
                description,
                SurfaceDetection.SYFT);
        technologies.forEach(technology -> add(surface, AttributeKind.TECHNOLOGY, technology));
        if (!UNATTRIBUTED.equals(path)) {
            add(surface, AttributeKind.FILE, path);
        }
        purls.forEach(purl -> add(surface, AttributeKind.IDENTIFIER, purl));
        add(surface, AttributeKind.IDENTIFIER, "direct:" + purls.size());
        if (transitive > 0) {
            add(surface, AttributeKind.IDENTIFIER, "transitive:" + transitive);
        }
        return surface;
    }

    private static String manifestPath(JsonNode component) {
        String path = property(component, "syft:location:0:path");
        if (path == null || path.isBlank() || path.equals("/")) {
            return UNATTRIBUTED;
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private static String purlType(String purl) {
        if (purl == null || !purl.startsWith("pkg:")) {
            return null;
        }
        int separator = purl.indexOf('/');
        if (separator <= 4) {
            return null;
        }
        return purl.substring(4, separator);
    }

    private static boolean testScope(String scope) {
        if (scope == null) return false;
        return List.of("test", "dev", "development", "test-only").contains(scope.toLowerCase(Locale.ROOT));
    }

    private static String property(JsonNode component, String name) {
        for (JsonNode property : component.path("properties")) {
            if (name.equals(property.path("name").asText())) {
                return property.path("value").asText(null);
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText(null);
    }

    private static void add(CtxSurface surface, AttributeKind kind, String value) {
        if (value != null
                && !value.isBlank()
                && surface.getAttributeRows().stream()
                        .noneMatch(
                                row -> row.getKind() == kind && row.getValue().equals(value))) {
            surface.addAttribute(new CtxSurfaceAttribute(surface, kind, value, SurfaceDetection.SYFT));
        }
    }
}
