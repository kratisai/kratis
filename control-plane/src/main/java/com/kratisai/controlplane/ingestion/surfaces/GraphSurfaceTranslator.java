package com.kratisai.controlplane.ingestion.surfaces;

import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
import com.kratisai.controlplane.model.AttributeKind;
import com.kratisai.controlplane.model.CtxSurface;
import com.kratisai.controlplane.model.CtxSurfaceAttribute;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.SurfaceDetection;
import com.kratisai.controlplane.model.SurfaceRole;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GraphSurfaceTranslator {
    private static final Logger log = LoggerFactory.getLogger(GraphSurfaceTranslator.class);
    private static final Set<String> IN_PROCESS_TRANSPORTS = Set.of("event_emitter", "in_process", "local", "memory");
    private static final Set<String> INTERNAL_GRAPH_EDGES = Set.of(
            "USAGE",
            "CONFIGURES",
            "DEFINES",
            "DEFINES_METHOD",
            "IMPORTS",
            "DECORATES",
            "WRITES",
            "READS",
            "CONTAINS_FILE",
            "CONTAINS_FOLDER",
            "TESTS",
            "SIMILAR_TO",
            "SEMANTICALLY_RELATED",
            "MENTIONS",
            "REFERENCES_FILE",
            "OVERRIDE",
            "IMPLEMENTS",
            "INHERITS",
            "CALL_REFERENCE",
            "SPAWNS",
            "HAS_BRANCH",
            "DIAGRAM_ARROW",
            "RAISES",
            "THROWS");
    private static final Pattern LINE_SUFFIX = Pattern.compile("^(.+):([1-9][0-9]*)$");
    private static final Pattern NAMED_PLACEHOLDER = Pattern.compile("\\{[^{}]+\\}");
    static final int MAX_NON_SYFT_ATTRIBUTES = 30;

    List<CtxSurface> translate(
            IngestionBatch batch, ParserGraph graph, IngestionBatchLogService.BatchLogger batchLogger) {
        Set<Long> ownRoutes = new HashSet<>();
        Set<String> ownRoutePaths = new HashSet<>();
        Set<Long> linkedRoutes = new HashSet<>();
        Map<String, List<ParserGraph.Node>> handlersByName = indexHandlers(graph);
        graph.nodes().values().forEach(node -> {
            if ("Route".equals(node.label()) && isSpecificationSource(text(node.props(), "source"))) {
                ownRoutes.add(node.id());
                ownRoutePaths.add(node.name());
            }
        });
        for (ParserGraph.Edge edge : graph.edges()) {
            ParserGraph.Node target = graph.nodes().get(edge.targetId());
            if ("HANDLES".equals(edge.type())
                    || ("CALLS".equals(edge.type())
                            && target != null
                            && "Route".equals(target.label())
                            && "route_registration".equals(text(edge.props(), "via")))) {
                ownRoutes.add(edge.targetId());
                if (target != null && !target.name().isBlank()) {
                    ownRoutePaths.add(target.name());
                }
            }
        }

        Map<GroupKey, List<CtxSurface>> groups = new LinkedHashMap<>();
        for (ParserGraph.Edge edge : graph.edges()) {
            if (INTERNAL_GRAPH_EDGES.contains(edge.type())) {
                continue;
            }
            ParserGraph.Node source = graph.nodes().get(edge.sourceId());
            ParserGraph.Node target = graph.nodes().get(edge.targetId());
            if (source == null || target == null) {
                debug(
                        batchLogger,
                        "Dropped dangling " + edge.type() + " edge (source=" + edge.sourceId() + ", target="
                                + edge.targetId() + ")");
                continue;
            }
            boolean registration = "CALLS".equals(edge.type())
                    && "Route".equals(target.label())
                    && "route_registration".equals(text(edge.props(), "via"));
            if ("CALLS".equals(edge.type()) && !registration) {
                continue;
            }
            if ("ASYNC_CALLS".equals(edge.type())) {
                if (shouldSkip(source, target)) {
                    continue;
                }
                String asyncTransport =
                        firstText(edge.props(), target.props(), "transport", "broker", "framework", "protocol");
                if (asyncTransport != null && IN_PROCESS_TRANSPORTS.contains(asyncTransport.toLowerCase(Locale.ROOT))) {
                    debug(
                            batchLogger,
                            "Excluded in-process ASYNC_CALLS (" + asyncTransport + ") " + target.name() + " at "
                                    + nodeLocation(source));
                } else {
                    debug(
                            batchLogger,
                            "Ignored unclassified ASYNC_CALLS to " + target.name() + " at " + nodeLocation(source));
                }
                continue;
            }
            SurfaceRole role =
                    switch (edge.type()) {
                        case "HANDLES" -> SurfaceRole.PROVIDES;
                        case "EMITS", "LISTENS_ON" -> SurfaceRole.BIDIRECTIONAL;
                        case "HTTP_CALLS" -> SurfaceRole.REQUIRES;
                        case "CALLS" -> SurfaceRole.PROVIDES;
                        case "DEPENDS_ON" -> "Image".equals(target.label()) ? SurfaceRole.REQUIRES : null;
                        default -> null;
                    };
            if (role == null) {
                debug(
                        batchLogger,
                        "Ignored " + edge.type() + " edge to " + target.label() + " " + target.name() + " at "
                                + nodeLocation(source));
                continue;
            }
            if ("HANDLES".equals(edge.type()) && !"Route".equals(target.label())) {
                debug(
                        batchLogger,
                        "Ignored HANDLES edge to non-Route " + target.label() + " " + target.name() + " at "
                                + nodeLocation(source));
                continue;
            }
            if (shouldSkip(source, target)) {
                continue;
            }
            String transport = firstText(edge.props(), target.props(), "transport", "broker", "framework", "protocol");
            if (transport != null && IN_PROCESS_TRANSPORTS.contains(transport.toLowerCase(Locale.ROOT))) {
                debug(
                        batchLogger,
                        "Excluded in-process " + edge.type() + " (" + transport + ") " + target.name() + " at "
                                + nodeLocation(source));
                continue;
            }
            String url = firstText(edge.props(), target.props(), "url_path", "path");
            if ("HTTP_CALLS".equals(edge.type()) && selfCall(url, edge.targetId(), ownRoutes, ownRoutePaths)) {
                debug(
                        batchLogger,
                        "Excluded self-call HTTP_CALLS " + firstNonBlank(url, target.name()) + " at "
                                + nodeLocation(source));
                continue;
            }
            if ("Route".equals(target.label())) {
                linkedRoutes.add(target.id());
            }

            CtxSurface surface = newSurface(batch, role, edge.type());
            String channel = firstNonBlank(firstText(edge.props(), target.props(), "topic", "channel"), target.name());
            if ("Route".equals(target.label())) {
                String endpoint = firstNonBlank(url, target.name());
                add(surface, AttributeKind.ENDPOINT, endpoint);
                add(surface, AttributeKind.METHOD, firstText(edge.props(), target.props(), "method"));
                if (endpoint == null || endpoint.isBlank()) {
                    addPrefixed(
                            surface,
                            AttributeKind.IDENTIFIER,
                            "target",
                            firstText(edge.props(), target.props(), "args", "callee"));
                }
            } else {
                add(surface, AttributeKind.IDENTIFIER, firstNonBlank(channel, target.name()));
            }
            add(surface, AttributeKind.TECHNOLOGY, transport);
            add(surface, AttributeKind.FILE, anchor(source));
            add(surface, AttributeKind.FILE, anchor(target));
            GroupKey groupKey = edgeGroup(edge.type(), source, url, transport, channel);
            groups.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(surface);
        }

        for (ParserGraph.Node node : graph.nodes().values()) {
            if (!"Route".equals(node.label()) || linkedRoutes.contains(node.id())) {
                continue;
            }
            if (isTest(node.props(), node.filePath())) {
                continue;
            }
            String routeSource = text(node.props(), "source");
            if (!isSpecificationSource(routeSource)) {
                debug(batchLogger, "Ignored unclassified Route " + node.name() + " at " + nodeLocation(node));
                continue;
            }
            CtxSurface surface = newSurface(batch, SurfaceRole.PROVIDES, "Route");
            add(surface, AttributeKind.ENDPOINT, node.name());
            add(surface, AttributeKind.METHOD, text(node.props(), "method"));
            add(surface, AttributeKind.FILE, anchor(node));
            addSpecificationHandler(surface, node, handlersByName);
            GroupKey groupKey = specificationRouteGroup(node);
            groups.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(surface);
        }
        addResourceNodes(batch, graph, batchLogger, groups);
        addBuildDefinitionNodes(batch, graph, groups);
        logGroups(groups);
        return merge(groups, batch);
    }

    private static void debug(IngestionBatchLogService.BatchLogger batchLogger, String message) {
        if (batchLogger != null) {
            batchLogger.debug("EXTRACT_SURFACES", message);
        }
    }

    private static String nodeLocation(ParserGraph.Node node) {
        String loc = anchor(node);
        return loc != null ? loc : node.name();
    }

    private static boolean isSpecificationSource(String source) {
        return "openapi".equals(source) || "proto".equals(source);
    }

    private static boolean shouldSkip(ParserGraph.Node source, ParserGraph.Node target) {
        Optional<Boolean> sourceTest = bool(source.props(), "is_test");
        Optional<Boolean> targetTest = bool(target.props(), "is_test");
        return sourceTest.equals(Optional.of(Boolean.TRUE))
                || targetTest.equals(Optional.of(Boolean.TRUE))
                || isTestPath(source.filePath())
                || isTestPath(target.filePath());
    }

    private static void addResourceNodes(
            IngestionBatch batch,
            ParserGraph graph,
            IngestionBatchLogService.BatchLogger batchLogger,
            Map<GroupKey, List<CtxSurface>> groups) {
        Map<Long, List<ParserGraph.Edge>> outgoing = new LinkedHashMap<>();
        for (ParserGraph.Edge edge : graph.edges()) {
            outgoing.computeIfAbsent(edge.sourceId(), ignored -> new ArrayList<>())
                    .add(edge);
        }
        for (ParserGraph.Node node : graph.nodes().values()) {
            if (!"Resource".equals(node.label())) {
                continue;
            }
            if (isTest(node.props(), node.filePath())) {
                continue;
            }
            CtxSurface surface = newSurface(batch, SurfaceRole.PROVIDES, "Resource");
            add(surface, AttributeKind.IDENTIFIER, node.name());
            String serviceName = text(node.props(), "service");
            if (serviceName != null && !serviceName.isBlank() && !serviceName.equals(node.name())) {
                add(surface, AttributeKind.IDENTIFIER, serviceName);
            }
            add(surface, AttributeKind.TECHNOLOGY, firstText(node.props(), Map.of(), "ecosystem", "source"));
            addPrefixed(surface, AttributeKind.IDENTIFIER, "kind", text(node.props(), "kind"));
            addPrefixed(surface, AttributeKind.IDENTIFIER, "group", text(node.props(), "group"));
            addPrefixed(surface, AttributeKind.IDENTIFIER, "version", text(node.props(), "version"));
            addPrefixed(surface, AttributeKind.IDENTIFIER, "obj_key", text(node.props(), "obj_key"));
            add(surface, AttributeKind.FILE, anchor(node));
            addInfrastructureReferences(
                    surface, outgoing.getOrDefault(node.id(), List.of()), graph.nodes(), batchLogger);
            GroupKey key = new GroupKey(
                    SurfaceRole.PROVIDES, "resource|" + file(node), "Resources declared in " + display(node));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(surface);
        }
    }

    private static void addInfrastructureReferences(
            CtxSurface surface,
            List<ParserGraph.Edge> edges,
            Map<Long, ParserGraph.Node> nodes,
            IngestionBatchLogService.BatchLogger batchLogger) {
        for (ParserGraph.Edge edge : edges) {
            ParserGraph.Node target = nodes.get(edge.targetId());
            if (target == null) {
                debug(
                        batchLogger,
                        "Dropped dangling " + edge.type() + " edge on Resource (target=" + edge.targetId() + ")");
                continue;
            }
            if (isTest(target.props(), target.filePath())) {
                continue;
            }
            if ("USAGE".equals(edge.type()) && "Image".equals(target.label())) {
                String image =
                        firstNonBlank(target.name(), text(target.props(), "repository"), text(edge.props(), "image"));
                add(surface, AttributeKind.IDENTIFIER, image);
                addPrefixed(surface, AttributeKind.IDENTIFIER, "registry", text(target.props(), "registry"));
                add(surface, AttributeKind.FILE, anchor(target));
            }
        }
    }

    private static void addBuildDefinitionNodes(
            IngestionBatch batch, ParserGraph graph, Map<GroupKey, List<CtxSurface>> groups) {
        for (ParserGraph.Node node : graph.nodes().values()) {
            if (!node.props().containsKey("image_context") && !node.props().containsKey("image_files")) {
                continue;
            }
            if (isTest(node.props(), node.filePath())) {
                continue;
            }
            CtxSurface surface = newSurface(batch, SurfaceRole.REQUIRES, "BuildDefinition");
            add(surface, AttributeKind.IDENTIFIER, node.name());
            addPrefixed(surface, AttributeKind.IDENTIFIER, "build_context", text(node.props(), "image_context"));
            for (String buildFile : stringList(node.props().get("image_files"))) {
                addPrefixed(surface, AttributeKind.IDENTIFIER, "build_file", buildFile);
            }
            add(surface, AttributeKind.FILE, anchor(node));
            GroupKey key =
                    new GroupKey(SurfaceRole.REQUIRES, "build|" + file(node), "Build definition in " + display(node));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(surface);
        }
    }

    private static GroupKey edgeGroup(
            String type, ParserGraph.Node source, String url, String transport, String channel) {
        return switch (type) {
            case "HANDLES", "CALLS" ->
                new GroupKey(
                        SurfaceRole.PROVIDES,
                        "http-provides|" + file(source),
                        "HTTP API provided by " + display(source));
            case "EMITS", "LISTENS_ON" -> messagingGroup(transport, channel);
            case "HTTP_CALLS" -> httpRequiresGroup(source, url);
            case "DEPENDS_ON" ->
                new GroupKey(
                        SurfaceRole.REQUIRES,
                        "deployment|" + file(source),
                        "Container images referenced by " + display(source));
            default -> throw new IllegalStateException("Unsupported surface edge type: " + type);
        };
    }

    private static GroupKey httpRequiresGroup(ParserGraph.Node source, String url) {
        String host = hostKey(url);
        return new GroupKey(
                SurfaceRole.REQUIRES,
                "http-requires|" + file(source) + "|" + host,
                "HTTP API used by " + display(source) + " on " + host);
    }

    private static GroupKey messagingGroup(String transport, String channel) {
        String actualTransport = transport == null || transport.isBlank() ? "messaging" : transport;
        String family = topicFamily(channel);
        String key = "messaging-bidirectional|" + actualTransport.toLowerCase(Locale.ROOT) + "|" + family;
        String description = actualTransport + " messaging" + (family.isBlank() ? "" : " on " + family);
        return new GroupKey(SurfaceRole.BIDIRECTIONAL, key, description);
    }

    private static GroupKey specificationRouteGroup(ParserGraph.Node node) {
        String source = text(node.props(), "source");
        String label = "openapi".equals(source) ? "HTTP" : "RPC";
        return new GroupKey(
                SurfaceRole.PROVIDES, "spec-" + source + "|" + file(node), label + " API specification " + file(node));
    }

    private static void logGroups(Map<GroupKey, List<CtxSurface>> groups) {
        groups.forEach((key, seeds) -> log.debug(
                "CMM group role={} key={} description={} observations={}",
                key.role(),
                key.key(),
                key.description(),
                seeds.stream().map(GraphSurfaceTranslator::summary).toList()));
    }

    private static String summary(CtxSurface seed) {
        return seed.getAttributeRows().stream()
                .map(attribute -> attribute.getKind() + "=" + debugValue(attribute))
                .toList()
                .toString();
    }

    private static String debugValue(CtxSurfaceAttribute attribute) {
        if (attribute.getKind() == AttributeKind.IDENTIFIER
                && attribute.getValue().startsWith("target:")) {
            return "target:<redacted>";
        }
        return attribute.getValue();
    }

    private static List<CtxSurface> merge(Map<GroupKey, List<CtxSurface>> groups, IngestionBatch batch) {
        return groups.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().key()))
                .map(entry -> {
                    GroupKey key = entry.getKey();
                    CtxSurface merged = new CtxSurface(
                            batch,
                            batch.getRepository().getId(),
                            batch.getRepository().getTeam().getId(),
                            key.role(),
                            key.description(),
                            SurfaceDetection.CMM);
                    for (CtxSurface seed : entry.getValue()) {
                        seed.getAttributeRows()
                                .forEach(attribute -> add(merged, attribute.getKind(), attribute.getValue()));
                    }
                    normalizeAttributes(merged);
                    return merged;
                })
                .toList();
    }

    static void normalizeAttributes(CtxSurface surface) {
        List<CtxSurfaceAttribute> current = new ArrayList<>(surface.getAttributeRows());
        if (current.isEmpty()) {
            return;
        }

        Set<String> namedSkeletons = new HashSet<>();
        for (CtxSurfaceAttribute attribute : current) {
            if (attribute.getKind() == AttributeKind.ENDPOINT
                    && !attribute.getValue().contains("{}")) {
                String skeleton = placeholderSkeleton(attribute.getValue());
                if (!skeleton.equals(attribute.getValue())) {
                    namedSkeletons.add(skeleton);
                }
            }
        }

        Map<String, Set<String>> fileAnchorsByPath = new LinkedHashMap<>();
        for (CtxSurfaceAttribute attribute : current) {
            if (attribute.getKind() == AttributeKind.FILE) {
                String basePath = stripLineSuffix(attribute.getValue());
                fileAnchorsByPath
                        .computeIfAbsent(basePath, ignored -> new LinkedHashSet<>())
                        .add(attribute.getValue());
            }
        }

        Set<String> higherSpecificityValues = new HashSet<>();
        for (CtxSurfaceAttribute attribute : current) {
            AttributeKind kind = attribute.getKind();
            if (kind == AttributeKind.ENDPOINT || kind == AttributeKind.METHOD || kind == AttributeKind.TECHNOLOGY) {
                if (kind == AttributeKind.ENDPOINT
                        && attribute.getValue().contains("{}")
                        && namedSkeletons.contains(placeholderSkeleton(attribute.getValue()))) {
                    continue;
                }
                higherSpecificityValues.add(attribute.getValue());
            }
        }

        surface.getAttributeRows().clear();
        Set<String> emittedCollapsedFiles = new HashSet<>();
        for (CtxSurfaceAttribute attribute : current) {
            AttributeKind kind = attribute.getKind();
            String value = attribute.getValue();
            if (kind == AttributeKind.ENDPOINT
                    && value.contains("{}")
                    && namedSkeletons.contains(placeholderSkeleton(value))) {
                continue;
            }
            if (kind == AttributeKind.FILE) {
                String basePath = stripLineSuffix(value);
                Set<String> anchors = fileAnchorsByPath.getOrDefault(basePath, Set.of());
                if (anchors.size() > 1) {
                    if (emittedCollapsedFiles.add(basePath)) {
                        add(surface, AttributeKind.FILE, basePath);
                    }
                    continue;
                }
            }
            if (kind == AttributeKind.IDENTIFIER && higherSpecificityValues.contains(value)) {
                continue;
            }
            add(surface, kind, value);
        }
        if (surface.getDetectedBy() != SurfaceDetection.SYFT
                && surface.getAttributeRows().size() > MAX_NON_SYFT_ATTRIBUTES) {
            capAttributes(surface);
        }
    }

    private static void capAttributes(CtxSurface surface) {
        List<CtxSurfaceAttribute> all = new ArrayList<>(surface.getAttributeRows());
        CtxSurfaceAttribute firstFile = all.stream()
                .filter(attr -> attr.getKind() == AttributeKind.FILE)
                .findFirst()
                .orElse(null);
        all.sort(Comparator.comparingInt(attr -> kindPriority(attr.getKind())));
        List<CtxSurfaceAttribute> kept = new ArrayList<>(all.subList(0, MAX_NON_SYFT_ATTRIBUTES));
        if (firstFile != null && kept.stream().noneMatch(attr -> attr.getKind() == AttributeKind.FILE)) {
            kept.set(kept.size() - 1, firstFile);
        }
        surface.getAttributeRows().clear();
        kept.forEach(surface::addAttribute);
    }

    private static int kindPriority(AttributeKind kind) {
        return switch (kind) {
            case ENDPOINT -> 1;
            case IDENTIFIER -> 2;
            case METHOD -> 3;
            case TECHNOLOGY -> 4;
            case FILE -> 5;
        };
    }

    private static String placeholderSkeleton(String endpoint) {
        return NAMED_PLACEHOLDER.matcher(endpoint).replaceAll("{}");
    }

    private static String stripLineSuffix(String fileAnchor) {
        Matcher matcher = LINE_SUFFIX.matcher(fileAnchor);
        return matcher.matches() ? matcher.group(1) : fileAnchor;
    }

    private static boolean selfCall(String url, long targetId, Set<Long> ownRoutes, Set<String> ownRoutePaths) {
        if (ownRoutes.contains(targetId)) {
            return true;
        }
        if (url == null || url.isBlank() || url.startsWith("http://") || url.startsWith("https://")) {
            return false;
        }
        String path = url.split("[?#]", 2)[0];
        if (path.isBlank()) {
            return false;
        }
        for (String route : ownRoutePaths) {
            if (route != null && !route.isBlank() && path.matches(routeRegex(route))) {
                return true;
            }
        }
        return false;
    }

    private static String routeRegex(String route) {
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < route.length(); ) {
            char current = route.charAt(index);
            if (current == '{') {
                int end = route.indexOf('}', index);
                if (end > index) {
                    regex.append("[^/]+");
                    index = end + 1;
                    continue;
                }
            }
            if (current == '*') {
                regex.append(".*");
                index++;
                continue;
            }
            regex.append(Pattern.quote(String.valueOf(current)));
            index++;
        }
        return regex.append("/?$").toString();
    }

    private static String hostKey(String url) {
        if (url == null || url.isBlank()) {
            return "unknown";
        }
        try {
            URI uri = new URI(url);
            if (uri.getHost() != null) {
                return uri.getHost();
            }
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                return "unknown";
            }
            String[] segments = path.replaceFirst("^/+", "").split("/");
            return segments.length == 0 || segments[0].isBlank() ? "unknown" : "path:" + segments[0];
        } catch (URISyntaxException ignored) {
            return "unresolved";
        }
    }

    private static String topicFamily(String channel) {
        if (channel == null || channel.isBlank()) {
            return "";
        }
        String trimmed = channel.trim();
        int separator = Math.min(positiveOrMax(trimmed.indexOf('.')), positiveOrMax(trimmed.indexOf('/')));
        return separator == Integer.MAX_VALUE ? trimmed : trimmed.substring(0, separator);
    }

    private static int positiveOrMax(int index) {
        return index > 0 ? index : Integer.MAX_VALUE;
    }

    private static String file(ParserGraph.Node node) {
        return node.filePath().isBlank() ? node.name() : node.filePath();
    }

    private static String display(ParserGraph.Node node) {
        return file(node);
    }

    private static CtxSurface newSurface(IngestionBatch batch, SurfaceRole role, String description) {
        return new CtxSurface(
                batch,
                batch.getRepository().getId(),
                batch.getRepository().getTeam().getId(),
                role,
                description,
                SurfaceDetection.CMM);
    }

    private static Map<String, List<ParserGraph.Node>> indexHandlers(ParserGraph graph) {
        Map<String, List<ParserGraph.Node>> result = new LinkedHashMap<>();
        for (ParserGraph.Node node : graph.nodes().values()) {
            if (("Function".equals(node.label()) || "Method".equals(node.label()))
                    && !node.name().isBlank()
                    && !isTest(node.props(), node.filePath())) {
                result.computeIfAbsent(node.name(), ignored -> new ArrayList<>())
                        .add(node);
            }
        }
        return result;
    }

    private static void addSpecificationHandler(
            CtxSurface surface, ParserGraph.Node route, Map<String, List<ParserGraph.Node>> handlersByName) {
        String operationId = text(route.props(), "operation_id");
        addPrefixed(surface, AttributeKind.IDENTIFIER, "operation_id", operationId);
        List<ParserGraph.Node> candidates =
                operationId == null ? List.of() : handlersByName.getOrDefault(operationId, List.of());
        if (candidates.size() != 1) {
            return;
        }
        ParserGraph.Node handler = candidates.getFirst();
        add(surface, AttributeKind.FILE, anchor(handler));
    }

    private static void addPrefixed(CtxSurface surface, AttributeKind kind, String prefix, String rawValue) {
        if (rawValue != null && !rawValue.isBlank()) {
            add(surface, kind, prefix + ":" + rawValue);
        }
    }

    private static void add(CtxSurface surface, AttributeKind kind, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        SurfaceDetection detection = surface.getDetectedBy() == null ? SurfaceDetection.CMM : surface.getDetectedBy();
        boolean duplicate = surface.getAttributeRows().stream()
                .anyMatch(existing ->
                        existing.getKind() == kind && existing.getValue().equals(value));
        if (!duplicate) {
            surface.addAttribute(new CtxSurfaceAttribute(surface, kind, value, detection));
        }
    }

    private static String anchor(ParserGraph.Node node) {
        if (node.filePath().isBlank()) {
            return null;
        }
        return node.startLine() > 0 ? node.filePath() + ":" + node.startLine() : node.filePath();
    }

    private static boolean isTest(Map<String, Object> props, String path) {
        return bool(props, "is_test").equals(Optional.of(Boolean.TRUE)) || isTestPath(path);
    }

    static boolean isTestPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        return normalized.startsWith("test/")
                || normalized.startsWith("tests/")
                || normalized.startsWith("cypress/")
                || normalized.startsWith("__tests__/")
                || normalized.contains("/test/")
                || normalized.contains("/tests/")
                || normalized.contains("/cypress/")
                || normalized.contains("/__tests__/")
                || fileName.endsWith("_test.go")
                || fileName.contains(".spec.")
                || fileName.contains(".test.");
    }

    private static List<String> stringList(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        return List.of();
    }

    private static Optional<Boolean> bool(Map<String, Object> props, String key) {
        Object value = props.get(key);
        return value instanceof Boolean flag ? Optional.of(flag) : Optional.empty();
    }

    private static String text(Map<String, Object> props, String key) {
        Object value = props.get(key);
        return value == null ? null : value.toString();
    }

    private static String firstText(Map<String, Object> first, Map<String, Object> second, String... keys) {
        for (String key : keys) {
            String value = text(first, key);
            if (value != null && !value.isBlank()) {
                return value;
            }
            value = text(second, key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record GroupKey(SurfaceRole role, String key, String description) {}
}
