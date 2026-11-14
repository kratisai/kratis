package com.kratisai.controlplane.ingestion.surfaces;

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
    private static final Pattern LINE_SUFFIX = Pattern.compile("^(.+):([1-9][0-9]*)$");
    private static final Pattern NAMED_PLACEHOLDER = Pattern.compile("\\{[^{}]+\\}");
    static final int MAX_NON_SYFT_ATTRIBUTES = 30;

    List<CtxSurface> translate(IngestionBatch batch, ParserGraph graph, SurfaceCounters counters) {
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
            if ("USAGE".equals(edge.type()) || "CONFIGURES".equals(edge.type())) {
                continue;
            }
            ParserGraph.Node source = graph.nodes().get(edge.sourceId());
            ParserGraph.Node target = graph.nodes().get(edge.targetId());
            if (source == null || target == null) {
                counters.increment("dropped.dangling_edge:" + edge.type());
                continue;
            }
            boolean registration = "CALLS".equals(edge.type())
                    && "Route".equals(target.label())
                    && "route_registration".equals(text(edge.props(), "via"));
            if ("CALLS".equals(edge.type()) && !registration) {
                if ("Route".equals(target.label())) {
                    counters.increment("ignored.edge:CALLS");
                } else if (source.filePath().equals(target.filePath())) {
                    counters.increment("cmm.ignored_same_file_call");
                } else if (!shouldSkip(source, target, counters)) {
                    counters.increment("cmm.unclassified_cross_file_call");
                }
                continue;
            }
            if ("ASYNC_CALLS".equals(edge.type())) {
                if (shouldSkip(source, target, counters)) {
                    continue;
                }
                String asyncTransport =
                        firstText(edge.props(), target.props(), "transport", "broker", "framework", "protocol");
                if (asyncTransport != null && IN_PROCESS_TRANSPORTS.contains(asyncTransport.toLowerCase(Locale.ROOT))) {
                    counters.increment("cmm.excluded_in_process");
                } else {
                    counters.increment("cmm.unclassified_async_call");
                }
                continue;
            }
            SurfaceRole role =
                    switch (edge.type()) {
                        case "HANDLES" -> SurfaceRole.PROVIDES;
                        case "EMITS" -> SurfaceRole.PROVIDES;
                        case "HTTP_CALLS", "LISTENS_ON" -> SurfaceRole.REQUIRES;
                        case "CALLS" -> SurfaceRole.PROVIDES;
                        case "DEPENDS_ON" -> "Image".equals(target.label()) ? SurfaceRole.REQUIRES : null;
                        default -> null;
                    };
            if (role == null) {
                counters.increment("ignored.edge:" + edge.type());
                continue;
            }
            if ("HANDLES".equals(edge.type()) && !"Route".equals(target.label())) {
                counters.increment("ignored.edge:HANDLES_non_route");
                continue;
            }
            if (shouldSkip(source, target, counters)) {
                continue;
            }
            String transport = firstText(edge.props(), target.props(), "transport", "broker", "framework", "protocol");
            if (transport != null && IN_PROCESS_TRANSPORTS.contains(transport.toLowerCase(Locale.ROOT))) {
                counters.increment("cmm.excluded_in_process");
                continue;
            }
            String url = firstText(edge.props(), target.props(), "url_path", "path");
            if ("HTTP_CALLS".equals(edge.type()) && selfCall(url, edge.targetId(), ownRoutes, ownRoutePaths)) {
                counters.increment("cmm.excluded_self_call");
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
                            AttributeKind.REF,
                            "target",
                            firstText(edge.props(), target.props(), "args", "callee"));
                }
            } else {
                add(surface, AttributeKind.IDENTIFIER, firstNonBlank(channel, target.name()));
            }
            add(surface, AttributeKind.TECHNOLOGY, transport);
            add(surface, AttributeKind.FILE, anchor(source));
            add(surface, AttributeKind.FILE, anchor(target));
            GroupKey groupKey = edgeGroup(edge.type(), role, source, url, transport, channel);
            groups.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(surface);
        }

        for (ParserGraph.Node node : graph.nodes().values()) {
            if (!"Route".equals(node.label()) || linkedRoutes.contains(node.id())) {
                continue;
            }
            if (isTest(node.props(), node.filePath())) {
                counters.increment("cmm.excluded_test");
                continue;
            }
            String routeSource = text(node.props(), "source");
            if (!isSpecificationSource(routeSource)) {
                counters.increment("cmm.unclassified_route");
                continue;
            }
            if (!bool(node.props(), "is_test").equals(Optional.of(Boolean.FALSE))) {
                counters.increment("cmm.unknown_scope");
            }
            CtxSurface surface = newSurface(batch, SurfaceRole.PROVIDES, "Route");
            add(surface, AttributeKind.ENDPOINT, node.name());
            add(surface, AttributeKind.METHOD, text(node.props(), "method"));
            add(surface, AttributeKind.FILE, anchor(node));
            addSpecificationHandler(surface, node, handlersByName, counters);
            GroupKey groupKey = specificationRouteGroup(node);
            groups.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(surface);
        }
        addResourceNodes(batch, graph, counters, groups);
        addBuildDefinitionNodes(batch, graph, counters, groups);
        logGroups(groups);
        return merge(groups, batch);
    }

    private static boolean isSpecificationSource(String source) {
        return "openapi".equals(source) || "proto".equals(source);
    }

    private static boolean shouldSkip(ParserGraph.Node source, ParserGraph.Node target, SurfaceCounters counters) {
        Optional<Boolean> sourceTest = bool(source.props(), "is_test");
        Optional<Boolean> targetTest = bool(target.props(), "is_test");
        if (sourceTest.equals(Optional.of(Boolean.TRUE))
                || targetTest.equals(Optional.of(Boolean.TRUE))
                || isTestPath(source.filePath())
                || isTestPath(target.filePath())) {
            counters.increment("cmm.excluded_test");
            return true;
        }
        if (sourceTest.isEmpty() && targetTest.isEmpty()) {
            counters.increment("cmm.unknown_scope");
        }
        return false;
    }

    private static void addResourceNodes(
            IngestionBatch batch, ParserGraph graph, SurfaceCounters counters, Map<GroupKey, List<CtxSurface>> groups) {
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
                counters.increment("cmm.excluded_test");
                continue;
            }
            CtxSurface surface = newSurface(batch, SurfaceRole.PROVIDES, "Resource");
            add(surface, AttributeKind.NAME, node.name());
            String serviceName = text(node.props(), "service");
            if (serviceName != null && !serviceName.isBlank() && !serviceName.equals(node.name())) {
                add(surface, AttributeKind.NAME, serviceName);
            }
            add(surface, AttributeKind.TECHNOLOGY, firstText(node.props(), Map.of(), "ecosystem", "source"));
            addPrefixed(surface, AttributeKind.REF, "kind", text(node.props(), "kind"));
            addPrefixed(surface, AttributeKind.REF, "group", text(node.props(), "group"));
            addPrefixed(surface, AttributeKind.REF, "version", text(node.props(), "version"));
            addPrefixed(surface, AttributeKind.REF, "obj_key", text(node.props(), "obj_key"));
            add(surface, AttributeKind.FILE, anchor(node));
            addInfrastructureReferences(surface, outgoing.getOrDefault(node.id(), List.of()), graph.nodes(), counters);
            GroupKey key = new GroupKey(
                    SurfaceRole.PROVIDES, "resource|" + file(node), "Resources declared in " + display(node));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(surface);
            counters.increment("cmm.resource_node");
        }
    }

    private static void addInfrastructureReferences(
            CtxSurface surface,
            List<ParserGraph.Edge> edges,
            Map<Long, ParserGraph.Node> nodes,
            SurfaceCounters counters) {
        for (ParserGraph.Edge edge : edges) {
            ParserGraph.Node target = nodes.get(edge.targetId());
            if (target == null) {
                counters.increment("dropped.dangling_edge:" + edge.type());
                continue;
            }
            if (isTest(target.props(), target.filePath())) {
                counters.increment("cmm.excluded_test");
                continue;
            }
            if ("USAGE".equals(edge.type()) && "Image".equals(target.label())) {
                String image =
                        firstNonBlank(target.name(), text(target.props(), "repository"), text(edge.props(), "image"));
                add(surface, AttributeKind.IDENTIFIER, image);
                addPrefixed(surface, AttributeKind.REF, "registry", text(target.props(), "registry"));
                add(surface, AttributeKind.FILE, anchor(target));
            } else if ("CONFIGURES".equals(edge.type()) && "EnvVar".equals(target.label())) {
                counters.increment("cmm.ignored_env_var");
            }
        }
    }

    private static void addBuildDefinitionNodes(
            IngestionBatch batch, ParserGraph graph, SurfaceCounters counters, Map<GroupKey, List<CtxSurface>> groups) {
        for (ParserGraph.Node node : graph.nodes().values()) {
            if (!node.props().containsKey("image_context") && !node.props().containsKey("image_files")) {
                continue;
            }
            if (isTest(node.props(), node.filePath())) {
                counters.increment("cmm.excluded_test");
                continue;
            }
            CtxSurface surface = newSurface(batch, SurfaceRole.PROVIDES, "BuildDefinition");
            add(surface, AttributeKind.NAME, node.name());
            addPrefixed(surface, AttributeKind.REF, "build_context", text(node.props(), "image_context"));
            for (String buildFile : stringList(node.props().get("image_files"))) {
                addPrefixed(surface, AttributeKind.REF, "build_file", buildFile);
            }
            add(surface, AttributeKind.FILE, anchor(node));
            GroupKey key =
                    new GroupKey(SurfaceRole.PROVIDES, "build|" + file(node), "Build definition in " + display(node));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(surface);
            counters.increment("cmm.build_definition");
        }
    }

    private static GroupKey edgeGroup(
            String type, SurfaceRole role, ParserGraph.Node source, String url, String transport, String channel) {
        return switch (type) {
            case "HANDLES", "CALLS" ->
                new GroupKey(
                        SurfaceRole.PROVIDES,
                        "http-provides|" + file(source),
                        "HTTP API provided by " + display(source));
            case "EMITS" -> messagingGroup(role, transport, channel, "provided");
            case "HTTP_CALLS" -> httpRequiresGroup(source, url);
            case "LISTENS_ON" -> messagingGroup(role, transport, channel, "consumed");
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

    private static GroupKey messagingGroup(SurfaceRole role, String transport, String channel, String verb) {
        String actualTransport = transport == null || transport.isBlank() ? "messaging" : transport;
        String family = topicFamily(channel);
        String key = "messaging-" + role.name().toLowerCase(Locale.ROOT) + "|"
                + actualTransport.toLowerCase(Locale.ROOT) + "|" + family;
        String description = actualTransport + " messages " + verb + (family.isBlank() ? "" : " on " + family);
        return new GroupKey(role, key, description);
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
        if (attribute.getKind() == AttributeKind.REF && attribute.getValue().startsWith("target:")) {
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
            if (kind == AttributeKind.ENDPOINT
                    || kind == AttributeKind.IDENTIFIER
                    || kind == AttributeKind.METHOD
                    || kind == AttributeKind.TECHNOLOGY
                    || kind == AttributeKind.NAME) {
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
            if (kind == AttributeKind.REF && isShadowedByHigherKind(value, higherSpecificityValues)) {
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
            case NAME -> 5;
            case FILE -> 6;
            case REF -> 7;
        };
    }

    private static String placeholderSkeleton(String endpoint) {
        return NAMED_PLACEHOLDER.matcher(endpoint).replaceAll("{}");
    }

    private static String stripLineSuffix(String fileAnchor) {
        Matcher matcher = LINE_SUFFIX.matcher(fileAnchor);
        return matcher.matches() ? matcher.group(1) : fileAnchor;
    }

    private static boolean isShadowedByHigherKind(String refValue, Set<String> higherSpecificityValues) {
        if (higherSpecificityValues.contains(refValue)) {
            return true;
        }
        int colon = refValue.indexOf(':');
        if (colon >= 0 && colon + 1 < refValue.length()) {
            return higherSpecificityValues.contains(refValue.substring(colon + 1));
        }
        return false;
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
            CtxSurface surface,
            ParserGraph.Node route,
            Map<String, List<ParserGraph.Node>> handlersByName,
            SurfaceCounters counters) {
        String operationId = text(route.props(), "operation_id");
        addPrefixed(surface, AttributeKind.REF, "operation_id", operationId);
        List<ParserGraph.Node> candidates =
                operationId == null ? List.of() : handlersByName.getOrDefault(operationId, List.of());
        if (candidates.size() != 1) {
            String protocol = "proto".equals(text(route.props(), "source")) ? "grpc" : "http";
            counters.increment("payload.no_handler:" + protocol);
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
