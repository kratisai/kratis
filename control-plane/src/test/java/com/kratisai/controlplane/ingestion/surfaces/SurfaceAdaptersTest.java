package com.kratisai.controlplane.ingestion.surfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.config.IngestionProperties;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
import com.kratisai.controlplane.ingestion.parse.CodebaseMemoryParserService;
import com.kratisai.controlplane.model.AttributeKind;
import com.kratisai.controlplane.model.CtxSurface;
import com.kratisai.controlplane.model.CtxSurfaceAttribute;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.SurfaceDetection;
import com.kratisai.controlplane.model.SurfaceRole;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.repository.CtxSurfaceAttributeRepository;
import com.kratisai.controlplane.repository.CtxSurfaceRepository;
import com.kratisai.controlplane.service.ProcessExecutor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SurfaceAdaptersTest {
    private IngestionBatch batch() {
        IngestionBatch batch = mock(IngestionBatch.class);
        Repository repository = mock(Repository.class);
        Team team = mock(Team.class);
        when(batch.getRepository()).thenReturn(repository);
        when(repository.getTeam()).thenReturn(team);
        when(repository.getId()).thenReturn(UUID.randomUUID());
        when(team.getId()).thenReturn(UUID.randomUUID());
        return batch;
    }

    @Test
    void syftGroupsDirectDependenciesPerManifest() throws Exception {
        var bom = new ObjectMapper().readTree("""
                {"components":[
                  {"bom-ref":"root", "name":"app", "version":"1.0.0", "purl":"pkg:npm/app@1.0.0",
                   "properties":[{"name":"syft:location:0:path","value":"/package-lock.json"},
                     {"name":"syft:package:scope","value":"runtime"}]},
                  {"bom-ref":"a", "name":"a", "version":"1", "purl":"pkg:npm/a@1",
                   "properties":[{"name":"syft:location:0:path","value":"/package-lock.json"},
                     {"name":"syft:package:scope","value":"runtime"}]},
                  {"bom-ref":"b", "name":"b", "version":"1", "purl":"pkg:unfamiliar/b@1",
                   "properties":[{"name":"syft:location:0:path","value":"/package-lock.json"},
                     {"name":"syft:package:scope","value":"runtime"}]},
                  {"bom-ref":"c", "name":"c", "version":"1", "purl":"pkg:npm/c@1",
                   "properties":[{"name":"syft:location:0:path","value":"/package-lock.json"},
                     {"name":"syft:package:scope","value":"runtime"}]},
                  {"bom-ref":"d", "name":"d", "version":"2", "purl":"pkg:pypi/d@2",
                   "properties":[{"name":"syft:location:0:path","value":"/requirements.txt"},
                     {"name":"syft:package:scope","value":"runtime"}]}
                ],
                "dependencies":[{"ref":"root","dependsOn":["a","b"]},{"ref":"a","dependsOn":["c"]}]}
                """);
        var batchLogger = mock(IngestionBatchLogService.BatchLogger.class);
        var surfaces = new SyftSurfaceAdapter(null, null, new ObjectMapper()).parse(batch(), bom, batchLogger);

        assertThat(surfaces).hasSize(3);
        CtxSurface provided = surfaces.stream()
                .filter(surface -> surface.getRole() == SurfaceRole.PROVIDES)
                .findFirst()
                .orElseThrow();
        assertThat(provided.getDescription()).isEqualTo("Package identity declared by package-lock.json");
        assertThat(provided.getAttributeRows())
                .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                .contains(tuple(AttributeKind.IDENTIFIER, "pkg:npm/app@1.0.0"), tuple(AttributeKind.IDENTIFIER, "app"));
        CtxSurface packages = surfaces.stream()
                .filter(surface -> surface.getDescription().contains("package-lock.json")
                        && surface.getRole() == SurfaceRole.REQUIRES)
                .findFirst()
                .orElseThrow();
        assertThat(packages.getAttributeRows())
                .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                .contains(
                        tuple(AttributeKind.FILE, "package-lock.json"),
                        tuple(AttributeKind.TECHNOLOGY, "npm"),
                        tuple(AttributeKind.TECHNOLOGY, "unfamiliar"),
                        tuple(AttributeKind.IDENTIFIER, "pkg:npm/a@1"),
                        tuple(AttributeKind.IDENTIFIER, "pkg:unfamiliar/b@1"),
                        tuple(AttributeKind.IDENTIFIER, "direct:2"),
                        tuple(AttributeKind.IDENTIFIER, "transitive:1"))
                .doesNotContain(
                        tuple(AttributeKind.IDENTIFIER, "pkg:npm/app@1.0.0"),
                        tuple(AttributeKind.IDENTIFIER, "pkg:npm/c@1"));
        CtxSurface python = surfaces.stream()
                .filter(surface -> surface.getRole() == SurfaceRole.REQUIRES
                        && surface.getAttributeRows().stream()
                                .anyMatch(attribute -> "requirements.txt".equals(attribute.getValue())))
                .findFirst()
                .orElseThrow();
        assertThat(python.getAttributeRows())
                .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                .contains(
                        tuple(AttributeKind.FILE, "requirements.txt"), tuple(AttributeKind.IDENTIFIER, "pkg:pypi/d@2"));
        verifyNoInteractions(batchLogger);
    }

    @Test
    void syftExcludesTestScopeAndKeepsUnknownScope() throws Exception {
        var bom = new ObjectMapper().readTree("""
                {"components":[
                  {"name":"test", "purl":"pkg:npm/test@1", "scope":"test",
                   "properties":[{"name":"syft:location:0:path","value":"/package.json"}]},
                  {"name":"unknown", "purl":"pkg:npm/unknown@1",
                   "properties":[{"name":"syft:location:0:path","value":"/package.json"}]},
                  {"name":"no-purl", "properties":[{"name":"syft:location:0:path","value":"/package.json"}]}
                ]}
                """);
        var batchLogger = mock(IngestionBatchLogService.BatchLogger.class);
        var surfaces = new SyftSurfaceAdapter(null, null, new ObjectMapper()).parse(batch(), bom, batchLogger);

        assertThat(surfaces).singleElement().satisfies(surface -> {
            assertThat(surface.getRole()).isEqualTo(SurfaceRole.REQUIRES);
            assertThat(surface.getAttributeRows())
                    .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                    .contains(
                            tuple(AttributeKind.FILE, "package.json"),
                            tuple(AttributeKind.IDENTIFIER, "pkg:npm/unknown@1"));
        });
    }

    @Test
    void syftRetainsAllDependencyGraphRoots() throws Exception {
        var bom = new ObjectMapper().readTree("""
                {"components":[
                  {"bom-ref":"web", "name":"web", "version":"0.0.0", "purl":"pkg:npm/web@0.0.0",
                   "properties":[{"name":"syft:location:0:path","value":"/package-lock.json"}]},
                  {"bom-ref":"a", "name":"a", "version":"1", "purl":"pkg:npm/a@1",
                   "properties":[{"name":"syft:location:0:path","value":"/package-lock.json"}]},
                  {"bom-ref":"wasm", "name":"@tailwindcss/oxide-wasm32-wasi", "version":"4.3.0",
                   "purl":"pkg:npm/%40tailwindcss/oxide-wasm32-wasi@4.3.0",
                   "properties":[{"name":"syft:location:0:path","value":"/package-lock.json"}]},
                  {"bom-ref":"platform", "name":"platform", "version":"1", "purl":"pkg:npm/platform@1",
                   "properties":[{"name":"syft:location:0:path","value":"/package-lock.json"}]}
                ],
                "dependencies":[
                  {"ref":"web","dependsOn":["a"]},
                  {"ref":"wasm","dependsOn":["platform"]}
                ]}
                """);
        var surfaces = new SyftSurfaceAdapter(null, null, new ObjectMapper()).parse(batch(), bom, null);

        assertThat(surfaces).hasSize(3);
        assertThat(surfaces.stream().filter(surface -> surface.getRole() == SurfaceRole.PROVIDES))
                .hasSize(2)
                .allSatisfy(surface -> assertThat(surface.getAttributeRows())
                        .anyMatch(attribute -> attribute.getKind() == AttributeKind.IDENTIFIER));
        assertThat(surfaces).anySatisfy(surface -> {
            assertThat(surface.getRole()).isEqualTo(SurfaceRole.REQUIRES);
            assertThat(surface.getAttributeRows())
                    .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                    .contains(
                            tuple(AttributeKind.IDENTIFIER, "pkg:npm/a@1"),
                            tuple(AttributeKind.IDENTIFIER, "pkg:npm/platform@1"));
        });
    }

    @Test
    void syftKeepsNonNpmGraphRootsAlongsideTheNpmRoot() throws Exception {
        var bom = new ObjectMapper().readTree("""
                {"components":[
                  {"bom-ref":"web", "name":"web", "version":"0.0.0", "purl":"pkg:npm/web@0.0.0",
                   "properties":[{"name":"syft:location:0:path","value":"/package-lock.json"}]},
                  {"bom-ref":"npm-dep", "name":"dep", "purl":"pkg:npm/dep@1",
                   "properties":[{"name":"syft:location:0:path","value":"/package-lock.json"}]},
                  {"bom-ref":"maven", "name":"app", "purl":"pkg:maven/com.example/app@1",
                   "properties":[{"name":"syft:location:0:path","value":"/pom.xml"}]},
                  {"bom-ref":"maven-dep", "name":"lib", "purl":"pkg:maven/com.example/lib@1",
                   "properties":[{"name":"syft:location:0:path","value":"/pom.xml"}]},
                  {"bom-ref":"go", "name":"module", "purl":"pkg:golang/example.com/module",
                   "properties":[{"name":"syft:location:0:path","value":"/go.mod"}]},
                  {"bom-ref":"go-dep", "name":"dep", "purl":"pkg:golang/example.com/dep@1",
                   "properties":[{"name":"syft:location:0:path","value":"/go.mod"}]}
                ],
                "dependencies":[
                  {"ref":"web","dependsOn":["npm-dep"]},
                  {"ref":"maven","dependsOn":["maven-dep"]},
                  {"ref":"go","dependsOn":["go-dep"]}
                ]}
                """);
        var surfaces = new SyftSurfaceAdapter(null, null, new ObjectMapper()).parse(batch(), bom, null);

        assertThat(surfaces).hasSize(6);
        assertThat(surfaces.stream().filter(surface -> surface.getRole() == SurfaceRole.PROVIDES))
                .hasSize(3)
                .allSatisfy(surface -> assertThat(surface.getAttributeRows())
                        .anyMatch(attribute -> attribute.getKind() == AttributeKind.IDENTIFIER));
        assertThat(surfaces.stream().filter(surface -> surface.getRole() == SurfaceRole.REQUIRES))
                .hasSize(3)
                .allSatisfy(surface -> assertThat(surface.getAttributeRows())
                        .anyMatch(attribute -> attribute.getKind() == AttributeKind.IDENTIFIER));
    }

    @Test
    void syftReportsEmptyDocuments() throws Exception {
        var mapper = new ObjectMapper();
        var adapter = new SyftSurfaceAdapter(null, null, new ObjectMapper());
        var batchLogger = mock(IngestionBatchLogService.BatchLogger.class);
        assertThat(adapter.parse(batch(), mapper.readTree("{}"), batchLogger)).isEmpty();
        assertThat(adapter.parse(batch(), mapper.readTree("{\"components\":[]}"), batchLogger))
                .isEmpty();
        verify(batchLogger, times(2)).debug("EXTRACT_SURFACES", "SBOM produced 0 components.");
    }

    @Test
    void routeSpecificationsRetainTheirMeaningAndUnclassifiedAsyncCallsAreCountedOnly() {
        var route = new ParserGraph.Node(1, "Route", "/orders", "api.yaml", 8, Map.of("source", "openapi"));
        var caller = new ParserGraph.Node(2, "Function", "publish", "src/main.java", 1, Map.of("is_test", false));
        var channel = new ParserGraph.Node(3, "Channel", "target", null, 0, Map.of());
        var testCaller =
                new ParserGraph.Node(4, "Function", "testPub", "tests/main_test.java", 1, Map.of("is_test", true));
        var graph = new ParserGraph(
                Map.of(1L, route, 2L, caller, 3L, channel, 4L, testCaller),
                List.of(
                        new ParserGraph.Edge(
                                2,
                                3,
                                "ASYNC_CALLS",
                                Map.of("callee", "publish", "broker", "custom", "topic", "actual")),
                        new ParserGraph.Edge(2, 3, "ASYNC_CALLS", Map.of("transport", "event_emitter")),
                        new ParserGraph.Edge(4, 3, "ASYNC_CALLS", Map.of("broker", "custom")),
                        new ParserGraph.Edge(2, 3, "EMITS", Map.of("transport", "event_emitter"))),
                List.of());
        var batchLogger = mock(IngestionBatchLogService.BatchLogger.class);
        var surfaces = new GraphSurfaceTranslator().translate(batch(), graph, batchLogger);
        assertThat(surfaces).singleElement().satisfies(surface -> {
            assertThat(surface.getRole()).isEqualTo(SurfaceRole.PROVIDES);
            assertThat(surface.getDescription()).isEqualTo("HTTP API specification api.yaml");
            assertThat(surface.getAttributeRows())
                    .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                    .contains(tuple(AttributeKind.ENDPOINT, "/orders"), tuple(AttributeKind.FILE, "api.yaml:8"));
        });
        verify(batchLogger).debug(eq("EXTRACT_SURFACES"), contains("Ignored unclassified ASYNC_CALLS"));
        verify(batchLogger).debug(eq("EXTRACT_SURFACES"), contains("Excluded in-process ASYNC_CALLS (event_emitter)"));
        verify(batchLogger).debug(eq("EXTRACT_SURFACES"), contains("Excluded in-process EMITS (event_emitter)"));
    }

    @Test
    void routeRegistrationMapsToProvidesAndRetainsCleanProductionAnchors() {
        var route = new ParserGraph.Node(1, "Route", "/orders", "", 0, Map.of("method", "POST"));
        var source = new ParserGraph.Node(2, "Module", "app", "src/app.js", 6, Map.of("is_test", false));
        var missingLine = new ParserGraph.Node(3, "Module", "api", "src/api.js", 0, Map.of());
        var test = new ParserGraph.Node(4, "Module", "test", "tests/app.test.js", 4, Map.of("is_test", true));
        var nonRoute = new ParserGraph.Node(5, "Channel", "not-a-route", "", 0, Map.of());
        var graph = new ParserGraph(
                Map.of(1L, route, 2L, source, 3L, missingLine, 4L, test, 5L, nonRoute),
                List.of(
                        new ParserGraph.Edge(2, 1, "CALLS", Map.of("via", "route_registration")),
                        new ParserGraph.Edge(3, 1, "CALLS", Map.of("via", "route_registration")),
                        new ParserGraph.Edge(4, 1, "CALLS", Map.of("via", "route_registration")),
                        new ParserGraph.Edge(999, 1, "CALLS", Map.of("via", "route_registration")),
                        new ParserGraph.Edge(2, 1, "CALLS", Map.of("via", "other")),
                        new ParserGraph.Edge(2, 5, "CALLS", Map.of("via", "route_registration"))),
                List.of());
        var batchLogger = mock(IngestionBatchLogService.BatchLogger.class);
        var surfaces = new GraphSurfaceTranslator().translate(batch(), graph, batchLogger);
        var registrations = surfaces.stream()
                .filter(surface -> surface.getDescription().startsWith("HTTP API provided by "))
                .toList();
        assertThat(registrations).hasSize(2).allSatisfy(surface -> {
            assertThat(surface.getRole()).isEqualTo(SurfaceRole.PROVIDES);
            assertThat(surface.getDetectedBy()).isEqualTo(SurfaceDetection.CMM);
            assertThat(surface.getAttributeRows())
                    .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                    .contains(tuple(AttributeKind.ENDPOINT, "/orders"), tuple(AttributeKind.METHOD, "POST"))
                    .doesNotContain(
                            tuple(AttributeKind.IDENTIFIER, "/orders"),
                            tuple(AttributeKind.IDENTIFIER, "via:route_registration"),
                            tuple(AttributeKind.IDENTIFIER, "signal:CALLS:route_registration"));
        });
        assertThat(registrations).anySatisfy(surface -> assertThat(surface.getAttributeRows())
                .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                .contains(tuple(AttributeKind.FILE, "src/app.js:6")));
        assertThat(registrations).anySatisfy(surface -> assertThat(surface.getAttributeRows())
                .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                .contains(tuple(AttributeKind.FILE, "src/api.js"))
                .doesNotContain(tuple(AttributeKind.FILE, "src/api.js:1")));
        verify(batchLogger).debug(eq("EXTRACT_SURFACES"), contains("Dropped dangling CALLS edge"));
    }

    @Test
    void cmmExcludesTestsPreservesUnknownTechnologyAndMissingLine() {
        var production = new ParserGraph.Node(1, "Function", "publish", "src/app.java", 0, Map.of("is_test", false));
        var test = new ParserGraph.Node(2, "Function", "publish", "tests/app.java", 5, Map.of("is_test", true));
        var unknown = new ParserGraph.Node(3, "Function", "publish", "src/unknown.java", 7, Map.of());
        var channel = new ParserGraph.Node(4, "Channel", "", "", 0, Map.of("transport", "NewBroker"));
        var graph = new ParserGraph(
                Map.of(1L, production, 2L, test, 3L, unknown, 4L, channel),
                List.of(
                        new ParserGraph.Edge(1, 4, "EMITS", Map.of()),
                        new ParserGraph.Edge(2, 4, "EMITS", Map.of()),
                        new ParserGraph.Edge(3, 4, "EMITS", Map.of())),
                List.of());
        var surfaces = new GraphSurfaceTranslator().translate(batch(), graph, null);
        assertThat(surfaces).singleElement().satisfies(surface -> assertThat(surface.getAttributeRows())
                .anySatisfy(a -> {
                    assertThat(a.getKind()).isEqualTo(AttributeKind.TECHNOLOGY);
                    assertThat(a.getValue()).isEqualTo("NewBroker");
                })
                .anySatisfy(a -> {
                    assertThat(a.getKind()).isEqualTo(AttributeKind.FILE);
                    assertThat(a.getValue()).isEqualTo("src/app.java");
                })
                .anySatisfy(a -> {
                    assertThat(a.getKind()).isEqualTo(AttributeKind.FILE);
                    assertThat(a.getValue()).isEqualTo("src/unknown.java:7");
                }));
    }

    @Test
    void specificationRoutesKeepIndependentFactsAndUniqueProductionHandlerTypes() {
        var openapi = new ParserGraph.Node(
                1,
                "Route",
                "/orders",
                "api/orders.yaml",
                12,
                Map.of("source", "openapi", "operation_id", "fetchOrder", "method", "GET", "is_test", false));
        var proto = new ParserGraph.Node(
                2,
                "Route",
                "catalog.List",
                "api/catalog.proto",
                7,
                Map.of("source", "proto", "operation_id", "listCatalog", "is_test", false));
        var handler = new ParserGraph.Node(
                3,
                "Method",
                "fetchOrder",
                "src/orders.java",
                25,
                Map.of(
                        "is_test",
                        false,
                        "param_types",
                        List.of("GetOrderRequest", "List<OrderFilter>"),
                        "return_type",
                        "ResponseEntity<Order>"));
        var testHandler = new ParserGraph.Node(
                4,
                "Method",
                "fetchOrder",
                "tests/orders.java",
                4,
                Map.of("is_test", true, "return_type", "TestResponse"));
        var caller = new ParserGraph.Node(5, "Function", "call", "src/client.java", 3, Map.of("is_test", false));
        var protoHandler = new ParserGraph.Node(
                6,
                "Function",
                "listCatalog",
                "src/catalog.go",
                0,
                Map.of("is_test", false, "param_types", "CatalogQuery", "return_type", "CatalogPage"));
        var unknown = new ParserGraph.Node(
                7, "Route", "/unknown", "api/unknown.yaml", 2, Map.of("source", "custom", "is_test", false));
        var testSpec = new ParserGraph.Node(
                8, "Route", "/test", "tests/test.yaml", 1, Map.of("source", "openapi", "is_test", true));
        var graph = new ParserGraph(
                Map.of(
                        1L,
                        openapi,
                        2L,
                        proto,
                        3L,
                        handler,
                        4L,
                        testHandler,
                        5L,
                        caller,
                        6L,
                        protoHandler,
                        7L,
                        unknown,
                        8L,
                        testSpec),
                List.of(
                        new ParserGraph.Edge(4, 1, "HANDLES", Map.of()),
                        new ParserGraph.Edge(999, 1, "HTTP_CALLS", Map.of()),
                        new ParserGraph.Edge(5, 1, "HTTP_CALLS", Map.of("url_path", "/orders")),
                        new ParserGraph.Edge(4, 2, "HANDLES", Map.of()),
                        new ParserGraph.Edge(999, 2, "HANDLES", Map.of())),
                List.of());
        var batchLogger = mock(IngestionBatchLogService.BatchLogger.class);
        var surfaces = new GraphSurfaceTranslator().translate(batch(), graph, batchLogger);
        assertThat(surfaces).hasSize(2);
        assertThat(surfaces).anySatisfy(surface -> {
            assertThat(surface.getDescription()).isEqualTo("HTTP API specification api/orders.yaml");
            assertThat(surface.getRole()).isEqualTo(SurfaceRole.PROVIDES);
            assertThat(surface.getAttributeRows())
                    .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                    .contains(
                            tuple(AttributeKind.ENDPOINT, "/orders"),
                            tuple(AttributeKind.METHOD, "GET"),
                            tuple(AttributeKind.FILE, "api/orders.yaml:12"),
                            tuple(AttributeKind.FILE, "src/orders.java:25"),
                            tuple(AttributeKind.IDENTIFIER, "operation_id:fetchOrder"))
                    .doesNotContain(
                            tuple(AttributeKind.FILE, "tests/orders.java:4"),
                            tuple(AttributeKind.IDENTIFIER, "GetOrderRequest"),
                            tuple(AttributeKind.IDENTIFIER, "List<OrderFilter>"),
                            tuple(AttributeKind.IDENTIFIER, "ResponseEntity<Order>"),
                            tuple(AttributeKind.IDENTIFIER, "TestResponse"));
        });
        assertThat(surfaces).anySatisfy(surface -> {
            assertThat(surface.getDescription()).isEqualTo("RPC API specification api/catalog.proto");
            assertThat(surface.getRole()).isEqualTo(SurfaceRole.PROVIDES);
            assertThat(surface.getAttributeRows())
                    .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                    .contains(
                            tuple(AttributeKind.ENDPOINT, "catalog.List"),
                            tuple(AttributeKind.FILE, "api/catalog.proto:7"),
                            tuple(AttributeKind.FILE, "src/catalog.go"),
                            tuple(AttributeKind.IDENTIFIER, "operation_id:listCatalog"))
                    .doesNotContain(
                            tuple(AttributeKind.FILE, "src/catalog.go:1"),
                            tuple(AttributeKind.IDENTIFIER, "CatalogQuery"),
                            tuple(AttributeKind.IDENTIFIER, "CatalogPage"));
        });
        verify(batchLogger).debug(eq("EXTRACT_SURFACES"), contains("Ignored unclassified Route /unknown"));
        verify(batchLogger).debug(eq("EXTRACT_SURFACES"), contains("Excluded self-call HTTP_CALLS /orders"));
        verify(batchLogger).debug(eq("EXTRACT_SURFACES"), contains("Dropped dangling HTTP_CALLS edge"));
        verify(batchLogger).debug(eq("EXTRACT_SURFACES"), contains("Dropped dangling HANDLES edge"));
    }

    @Test
    void cmmGroupsProvidersByHandlerAndClientsBySourceAndHostWithoutLosingFacts() {
        var orderController = new ParserGraph.Node(
                1,
                "Method",
                "getOrder",
                "src/OrderController.java",
                10,
                Map.of("is_test", false, "return_type", "Order"));
        var postOrder = new ParserGraph.Node(
                2, "Method", "postOrder", "src/OrderController.java", 20, Map.of("is_test", false));
        var getRoute = new ParserGraph.Node(3, "Route", "/orders/{id}", "", 0, Map.of("method", "GET"));
        var postRoute = new ParserGraph.Node(4, "Route", "/orders", "", 0, Map.of("method", "POST"));
        var client = new ParserGraph.Node(5, "Function", "callOrders", "src/client.ts", 5, Map.of("is_test", false));
        var bookingClient = new ParserGraph.Node(
                6, "Function", "callBooking", "src/booking-client.ts", 2, Map.of("is_test", false));
        var bookingRoute = new ParserGraph.Node(7, "Route", "/v1/bookings", "", 0, Map.of());
        var bookingRoute2 = new ParserGraph.Node(8, "Route", "/v1/bookings/{id}", "", 0, Map.of());
        var ws = new ParserGraph.Node(9, "Function", "connect", "src/ws.ts", 3, Map.of("is_test", false));
        var eventA = new ParserGraph.Node(10, "Channel", "eventA", "", 0, Map.of("transport", "websocket"));
        var eventB = new ParserGraph.Node(11, "Channel", "eventB", "", 0, Map.of("transport", "websocket"));
        var graph = new ParserGraph(
                Map.ofEntries(
                        Map.entry(1L, orderController),
                        Map.entry(2L, postOrder),
                        Map.entry(3L, getRoute),
                        Map.entry(4L, postRoute),
                        Map.entry(5L, client),
                        Map.entry(6L, bookingClient),
                        Map.entry(7L, bookingRoute),
                        Map.entry(8L, bookingRoute2),
                        Map.entry(9L, ws),
                        Map.entry(10L, eventA),
                        Map.entry(11L, eventB)),
                List.of(
                        new ParserGraph.Edge(1, 3, "HANDLES", Map.of()),
                        new ParserGraph.Edge(2, 4, "HANDLES", Map.of()),
                        new ParserGraph.Edge(5, 4, "HTTP_CALLS", Map.of("url_path", "/orders")),
                        new ParserGraph.Edge(
                                6,
                                7,
                                "HTTP_CALLS",
                                Map.of("url_path", "https://booking.example/v1/bookings", "method", "GET")),
                        new ParserGraph.Edge(
                                6,
                                8,
                                "HTTP_CALLS",
                                Map.of("url_path", "https://booking.example/v1/bookings/42", "method", "GET")),
                        new ParserGraph.Edge(9, 10, "LISTENS_ON", Map.of()),
                        new ParserGraph.Edge(9, 11, "LISTENS_ON", Map.of()),
                        new ParserGraph.Edge(9, 10, "EMITS", Map.of()),
                        new ParserGraph.Edge(9, 11, "EMITS", Map.of())),
                List.of());
        var batchLogger = mock(IngestionBatchLogService.BatchLogger.class);
        var surfaces = new GraphSurfaceTranslator().translate(batch(), graph, batchLogger);

        assertThat(surfaces).hasSize(4);
        assertThat(surfaces).anySatisfy(surface -> {
            assertThat(surface.getDescription()).isEqualTo("HTTP API provided by src/OrderController.java");
            assertThat(surface.getRole()).isEqualTo(SurfaceRole.PROVIDES);
            assertThat(surface.getAttributeRows())
                    .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                    .containsExactlyInAnyOrder(
                            tuple(AttributeKind.ENDPOINT, "/orders/{id}"),
                            tuple(AttributeKind.ENDPOINT, "/orders"),
                            tuple(AttributeKind.METHOD, "GET"),
                            tuple(AttributeKind.METHOD, "POST"),
                            tuple(AttributeKind.FILE, "src/OrderController.java"));
        });
        assertThat(surfaces).anySatisfy(surface -> {
            assertThat(surface.getDescription()).isEqualTo("HTTP API used by src/booking-client.ts on booking.example");
            assertThat(surface.getRole()).isEqualTo(SurfaceRole.REQUIRES);
            assertThat(surface.getAttributeRows())
                    .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                    .contains(
                            tuple(AttributeKind.ENDPOINT, "https://booking.example/v1/bookings"),
                            tuple(AttributeKind.ENDPOINT, "https://booking.example/v1/bookings/42"));
        });
        for (String channel : List.of("eventA", "eventB")) {
            assertThat(surfaces).anySatisfy(surface -> {
                assertThat(surface.getDescription()).isEqualTo("websocket messaging on " + channel);
                assertThat(surface.getRole()).isEqualTo(SurfaceRole.BIDIRECTIONAL);
                assertThat(surface.getAttributeRows())
                        .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                        .contains(tuple(AttributeKind.IDENTIFIER, channel));
            });
        }
        verify(batchLogger).debug(eq("EXTRACT_SURFACES"), contains("Excluded self-call HTTP_CALLS /orders"));
    }

    @Test
    void specificationRoutesDoNotGuessAnAmbiguousOrMissingHandler() {
        var openapi = new ParserGraph.Node(
                1, "Route", "/orders", "api.yaml", 5, Map.of("source", "openapi", "operation_id", "find"));
        var proto = new ParserGraph.Node(
                2, "Route", "catalog.List", "catalog.proto", 6, Map.of("source", "proto", "operation_id", "missing"));
        var first = new ParserGraph.Node(3, "Method", "find", "src/a.java", 1, Map.of("return_type", "First"));
        var second = new ParserGraph.Node(4, "Function", "find", "src/b.java", 2, Map.of("return_type", "Second"));
        var graph = new ParserGraph(Map.of(1L, openapi, 2L, proto, 3L, first, 4L, second), List.of(), List.of());
        var surfaces = new GraphSurfaceTranslator().translate(batch(), graph, null);
        assertThat(surfaces).hasSize(2).allSatisfy(surface -> {
            assertThat(surface.getRole()).isEqualTo(SurfaceRole.PROVIDES);
            assertThat(surface.getAttributeRows())
                    .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                    .doesNotContain(
                            tuple(AttributeKind.IDENTIFIER, "First"),
                            tuple(AttributeKind.IDENTIFIER, "Second"),
                            tuple(AttributeKind.FILE, "src/a.java:1"),
                            tuple(AttributeKind.FILE, "src/b.java:2"));
        });
    }

    @Test
    void graphTranslatesRolesAndFiltersInvalidEdgesAndTestRoutes() {
        var source = new ParserGraph.Node(
                1,
                "Function",
                "call",
                "src/main.java",
                3,
                Map.of("is_test", false, "return_type", "Result", "param_types", List.of("Request", "Token")));
        var route = new ParserGraph.Node(2, "Route", "/orders", "api.yaml", 4, Map.of("method", "POST"));
        var image = new ParserGraph.Node(3, "Image", "example/image", "Dockerfile", 0, Map.of());
        var unknownRoute = new ParserGraph.Node(4, "Route", "/other", "other.yaml", 0, Map.of("source", "custom"));
        var testRoute = new ParserGraph.Node(5, "Route", "/test", "src/order.spec.ts", 2, Map.of("is_test", true));
        var unknownLabel = new ParserGraph.Node(6, "UnknownLabel", "unknown", "src/unknown", 0, Map.of());
        var graph = new ParserGraph(
                Map.of(1L, source, 2L, route, 3L, image, 4L, unknownRoute, 5L, testRoute, 6L, unknownLabel),
                List.of(
                        new ParserGraph.Edge(1, 2, "HANDLES", Map.of("url_path", "/orders", "method", "POST")),
                        new ParserGraph.Edge(1, 3, "DEPENDS_ON", Map.of()),
                        new ParserGraph.Edge(
                                1, 3, "ASYNC_CALLS", Map.of("callee", "client:consume", "broker", "AlienQueue")),
                        new ParserGraph.Edge(1, 3, "ASYNC_CALLS", Map.of("callee", "custom")),
                        new ParserGraph.Edge(1, 3, "ASYNC_CALLS", Map.of()),
                        new ParserGraph.Edge(1, 2, "DEPENDS_ON", Map.of()),
                        new ParserGraph.Edge(1, 3, "HANDLES", Map.of()),
                        new ParserGraph.Edge(1, 6, "DEFINES", Map.of()),
                        new ParserGraph.Edge(1, 6, "IMPORTS", Map.of()),
                        new ParserGraph.Edge(1, 6, "WRITES", Map.of()),
                        new ParserGraph.Edge(1, 6, "DECORATES", Map.of()),
                        new ParserGraph.Edge(1, 6, "CONTAINS_FILE", Map.of()),
                        new ParserGraph.Edge(1, 3, "UNRECOGNIZED", Map.of()),
                        new ParserGraph.Edge(999, 3, "EMITS", Map.of())),
                List.of());
        var batchLogger = mock(IngestionBatchLogService.BatchLogger.class);
        var surfaces = new GraphSurfaceTranslator().translate(batch(), graph, batchLogger);
        assertThat(surfaces).hasSize(2);
        assertThat(surfaces).noneMatch(surface -> surface.getRole() == SurfaceRole.UNKNOWN);
        CtxSurface handled = surfaces.stream()
                .filter(surface -> "HTTP API provided by src/main.java".equals(surface.getDescription()))
                .findFirst()
                .orElseThrow();
        assertThat(handled.getRole()).isEqualTo(SurfaceRole.PROVIDES);
        assertThat(handled.getAttributeRows())
                .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                .containsExactlyInAnyOrder(
                        tuple(AttributeKind.ENDPOINT, "/orders"),
                        tuple(AttributeKind.METHOD, "POST"),
                        tuple(AttributeKind.FILE, "src/main.java:3"),
                        tuple(AttributeKind.FILE, "api.yaml:4"));
        verify(batchLogger, times(3)).debug(eq("EXTRACT_SURFACES"), contains("Ignored unclassified ASYNC_CALLS"));
        verify(batchLogger).debug(eq("EXTRACT_SURFACES"), contains("Ignored unclassified Route /other"));
        verify(batchLogger).debug(eq("EXTRACT_SURFACES"), contains("Ignored DEPENDS_ON edge"));
        verify(batchLogger).debug(eq("EXTRACT_SURFACES"), contains("Ignored HANDLES edge to non-Route"));
        verify(batchLogger).debug(eq("EXTRACT_SURFACES"), contains("Ignored UNRECOGNIZED edge"));
        verify(batchLogger).debug(eq("EXTRACT_SURFACES"), contains("Dropped dangling EMITS edge"));
        verify(batchLogger, never()).debug(eq("EXTRACT_SURFACES"), contains("Ignored DEFINES edge"));
        verify(batchLogger, never()).debug(eq("EXTRACT_SURFACES"), contains("Ignored IMPORTS edge"));
        verify(batchLogger, never()).debug(eq("EXTRACT_SURFACES"), contains("Ignored WRITES edge"));
        verify(batchLogger, never()).debug(eq("EXTRACT_SURFACES"), contains("Ignored DECORATES edge"));
        verify(batchLogger, never()).debug(eq("EXTRACT_SURFACES"), contains("Ignored CONTAINS_FILE edge"));
    }

    @Test
    void cmmRetainsParserLabelledResourcesImagesEnvironmentAndBuildContextWithoutDuplicates() {
        var kubernetesResource = new ParserGraph.Node(
                1,
                "Resource",
                "Deployment/supplychain",
                "deployment.yaml",
                1,
                Map.of(
                        "source", "k8s",
                        "group", "apps",
                        "kind", "Deployment",
                        "version", "v1",
                        "obj_key", "apps/Deployment/?/supplychain"));
        var image = new ParserGraph.Node(
                2,
                "Image",
                "ghcr.io/glark-newco/supplychain",
                "",
                0,
                Map.of("repository", "ghcr.io/glark-newco/supplychain", "registry", "ghcr.io"));
        var composeService = new ParserGraph.Node(
                3,
                "Resource",
                "Service/web",
                "docker-compose.yml",
                3,
                Map.of("ecosystem", "compose", "kind", "Service", "service", "web"));
        var environment = new ParserGraph.Node(
                4,
                "EnvVar",
                "POSTGRES_DB",
                "docker-compose.yml",
                9,
                Map.of(
                        "env_key", "POSTGRES_DB",
                        "scope", "container",
                        "container", "database",
                        "source", "compose"));
        var secretEnvironment = new ParserGraph.Node(
                6,
                "EnvVar",
                "POSTGRES_PASSWORD",
                "docker-compose.yml",
                10,
                Map.of("env_key", "POSTGRES_PASSWORD", "source", "compose"));
        var flagEnvironment = new ParserGraph.Node(
                7,
                "EnvVar",
                "STORE_MODEL_IN_DB",
                "docker-compose.yml",
                11,
                Map.of("env_key", "STORE_MODEL_IN_DB", "source", "compose"));
        var dockerfile = new ParserGraph.Node(
                5,
                "Module",
                "Dockerfile",
                "Dockerfile",
                1,
                Map.of("image_context", "", "image_files", List.of("lib", "package.json")));
        var graph = new ParserGraph(
                Map.ofEntries(
                        Map.entry(1L, kubernetesResource),
                        Map.entry(2L, image),
                        Map.entry(3L, composeService),
                        Map.entry(4L, environment),
                        Map.entry(5L, dockerfile),
                        Map.entry(6L, secretEnvironment),
                        Map.entry(7L, flagEnvironment)),
                List.of(
                        new ParserGraph.Edge(
                                1,
                                2,
                                "USAGE",
                                Map.of("ref", "image", "image", "ghcr.io/glark-newco/supplychain:latest")),
                        new ParserGraph.Edge(3, 4, "CONFIGURES", Map.of("strategy", "env_set", "source", "compose")),
                        new ParserGraph.Edge(3, 6, "CONFIGURES", Map.of("strategy", "env_set", "source", "compose")),
                        new ParserGraph.Edge(3, 7, "CONFIGURES", Map.of("strategy", "env_set", "source", "compose"))),
                List.of());

        var surfaces = new GraphSurfaceTranslator().translate(batch(), graph, null);

        assertThat(surfaces).hasSize(3);
        assertThat(surfaces).anySatisfy(surface -> {
            assertThat(surface.getDescription()).isEqualTo("Resources declared in deployment.yaml");
            assertThat(surface.getRole()).isEqualTo(SurfaceRole.PROVIDES);
            assertThat(surface.getAttributeRows())
                    .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                    .containsExactlyInAnyOrder(
                            tuple(AttributeKind.IDENTIFIER, "Deployment/supplychain"),
                            tuple(AttributeKind.TECHNOLOGY, "k8s"),
                            tuple(AttributeKind.IDENTIFIER, "kind:Deployment"),
                            tuple(AttributeKind.IDENTIFIER, "group:apps"),
                            tuple(AttributeKind.IDENTIFIER, "version:v1"),
                            tuple(AttributeKind.IDENTIFIER, "obj_key:apps/Deployment/?/supplychain"),
                            tuple(AttributeKind.IDENTIFIER, "ghcr.io/glark-newco/supplychain"),
                            tuple(AttributeKind.IDENTIFIER, "registry:ghcr.io"),
                            tuple(AttributeKind.FILE, "deployment.yaml:1"));
        });
        assertThat(surfaces).anySatisfy(surface -> {
            assertThat(surface.getDescription()).isEqualTo("Resources declared in docker-compose.yml");
            assertThat(surface.getAttributeRows())
                    .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                    .containsExactlyInAnyOrder(
                            tuple(AttributeKind.IDENTIFIER, "Service/web"),
                            tuple(AttributeKind.IDENTIFIER, "web"),
                            tuple(AttributeKind.TECHNOLOGY, "compose"),
                            tuple(AttributeKind.IDENTIFIER, "kind:Service"),
                            tuple(AttributeKind.FILE, "docker-compose.yml:3"));
        });
        assertThat(surfaces).anySatisfy(surface -> {
            assertThat(surface.getDescription()).isEqualTo("Build definition in Dockerfile");
            assertThat(surface.getRole()).isEqualTo(SurfaceRole.REQUIRES);
            assertThat(surface.getAttributeRows())
                    .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                    .containsExactlyInAnyOrder(
                            tuple(AttributeKind.IDENTIFIER, "Dockerfile"),
                            tuple(AttributeKind.IDENTIFIER, "build_file:lib"),
                            tuple(AttributeKind.IDENTIFIER, "build_file:package.json"),
                            tuple(AttributeKind.FILE, "Dockerfile:1"));
        });
    }

    @Test
    void cmmCountsInternalCrossFileCallsWithoutPersistingUnknownSurfacesAndCollapsesPlaceholders() {
        var callerA = new ParserGraph.Node(
                1, "Function", "openSession", "src/ui/SessionProvider.ts", 14, Map.of("is_test", false));
        var callerB = new ParserGraph.Node(
                2, "Method", "publishOrder", "src/ui/OrderClient.ts", 28, Map.of("is_test", false));
        var calleeA = new ParserGraph.Node(
                3, "Function", "createChannel", "src/network/channel.ts", 4, Map.of("return_type", "Channel"));
        var calleeB = new ParserGraph.Node(
                4, "Function", "submitRequest", "src/network/request.ts", 9, Map.of("return_type", "Response"));
        var local = new ParserGraph.Node(5, "Function", "refreshSession", "src/ui/SessionProvider.ts", 31, Map.of());
        var client = new ParserGraph.Node(
                6, "Function", "fetchUser", "src/ui/UsersService.ts", 12, Map.of("is_test", false));
        var unnamedRoute = new ParserGraph.Node(7, "Route", "/portalusers/{}", "", 0, Map.of("method", "GET"));
        var namedRoute = new ParserGraph.Node(8, "Route", "/portalusers/{id}", "", 0, Map.of("method", "GET"));
        var dynamicRoute = new ParserGraph.Node(9, "Route", "", "", 0, Map.of());
        var dynamicCaller = new ParserGraph.Node(
                10, "Function", "fetchDynamic", "src/ui/DynamicClient.ts", 5, Map.of("is_test", false));
        var graph = new ParserGraph(
                Map.ofEntries(
                        Map.entry(1L, callerA),
                        Map.entry(2L, callerB),
                        Map.entry(3L, calleeA),
                        Map.entry(4L, calleeB),
                        Map.entry(5L, local),
                        Map.entry(6L, client),
                        Map.entry(7L, unnamedRoute),
                        Map.entry(8L, namedRoute),
                        Map.entry(9L, dynamicRoute),
                        Map.entry(10L, dynamicCaller)),
                List.of(
                        new ParserGraph.Edge(1, 3, "CALLS", Map.of("callee", "createChannel", "args", "session.url")),
                        new ParserGraph.Edge(
                                2, 4, "CALLS", Map.of("callee", "submitRequest", "args", "order.endpoint")),
                        new ParserGraph.Edge(1, 5, "CALLS", Map.of("callee", "refreshSession")),
                        new ParserGraph.Edge(
                                6, 7, "HTTP_CALLS", Map.of("url_path", "/portalusers/{}", "method", "GET")),
                        new ParserGraph.Edge(
                                6, 8, "HTTP_CALLS", Map.of("url_path", "/portalusers/{id}", "method", "GET")),
                        new ParserGraph.Edge(10, 9, "HTTP_CALLS", Map.of("args", "API_BASE_URL"))),
                List.of());
        var batchLogger = mock(IngestionBatchLogService.BatchLogger.class);

        var surfaces = new GraphSurfaceTranslator().translate(batch(), graph, batchLogger);

        assertThat(surfaces).hasSize(2).noneMatch(surface -> surface.getRole() == SurfaceRole.UNKNOWN);
        assertThat(surfaces).anySatisfy(surface -> {
            assertThat(surface.getDescription()).isEqualTo("HTTP API used by src/ui/UsersService.ts on unresolved");
            assertThat(surface.getAttributeRows())
                    .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                    .containsExactlyInAnyOrder(
                            tuple(AttributeKind.ENDPOINT, "/portalusers/{id}"),
                            tuple(AttributeKind.METHOD, "GET"),
                            tuple(AttributeKind.FILE, "src/ui/UsersService.ts:12"));
        });
        assertThat(surfaces).anySatisfy(surface -> {
            assertThat(surface.getDescription()).isEqualTo("HTTP API used by src/ui/DynamicClient.ts on unknown");
            assertThat(surface.getAttributeRows())
                    .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                    .containsExactlyInAnyOrder(
                            tuple(AttributeKind.IDENTIFIER, "target:API_BASE_URL"),
                            tuple(AttributeKind.FILE, "src/ui/DynamicClient.ts:5"));
        });
        verifyNoInteractions(batchLogger);
    }

    @Test
    void cmmExcludesGoTestFileEdgesButKeepsProductionWebsocketEdge() {
        var testSource = new ParserGraph.Node(
                1, "File", "client_test.go", "sidecar/rpc/client_test.go", 0, Map.of("is_test", true));
        var productionSource = new ParserGraph.Node(2, "File", "client.go", "sidecar/rpc/client.go", 0, Map.of());
        var channel = new ParserGraph.Node(3, "Channel", "messages", "", 0, Map.of("transport", "websocket"));
        var graph = new ParserGraph(
                Map.of(1L, testSource, 2L, productionSource, 3L, channel),
                List.of(
                        new ParserGraph.Edge(1, 3, "LISTENS_ON", Map.of()),
                        new ParserGraph.Edge(2, 3, "LISTENS_ON", Map.of())),
                List.of());

        var surfaces = new GraphSurfaceTranslator().translate(batch(), graph, null);

        assertThat(surfaces).singleElement().satisfies(surface -> {
            assertThat(surface.getRole()).isEqualTo(SurfaceRole.BIDIRECTIONAL);
            assertThat(surface.getDescription()).isEqualTo("websocket messaging on messages");
            assertThat(surface.getAttributeRows())
                    .extracting(CtxSurfaceAttribute::getKind, CtxSurfaceAttribute::getValue)
                    .contains(tuple(AttributeKind.FILE, "sidecar/rpc/client.go"))
                    .doesNotContain(tuple(AttributeKind.FILE, "sidecar/rpc/client_test.go"));
        });
    }

    @Test
    void extractionFailsBeforeRunningAdaptersWhenParserDatabaseMissing(@TempDir Path root) {
        var parser = mock(CodebaseMemoryParserService.class);
        var graph = mock(GraphSurfaceAdapter.class);
        var syft = mock(SyftSurfaceAdapter.class);
        var surfaces = mock(CtxSurfaceRepository.class);
        var attributes = mock(CtxSurfaceAttributeRepository.class);
        IngestionBatch batch = batch();
        UUID batchId = UUID.randomUUID();
        when(batch.getId()).thenReturn(batchId);
        when(parser.sqlitePathFor(batch)).thenReturn(root.resolve("missing.db"));
        var service = new SurfaceExtractionService(parser, graph, syft, surfaces, attributes);
        var batchLogger = mock(IngestionBatchLogService.BatchLogger.class);

        assertThatThrownBy(() -> service.extract(batch, root, batchLogger))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Parser database missing")
                .hasMessageContaining(batchId.toString());
        verify(graph, never()).extract(any(), any(), any(), any());
        verify(syft, never()).extract(any(), any(), any());
        verify(surfaces, never()).saveAll(any());
        verify(attributes, never()).saveAll(any());
    }

    @Test
    void parserReaderRestrictsProjectsCountsCoverageAndToleratesMalformedProperties(@TempDir Path root)
            throws Exception {
        Path database = root.resolve("graph.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE nodes (id INTEGER, label TEXT, name TEXT, file_path TEXT, start_line INTEGER, properties TEXT, project TEXT)");
            statement.execute(
                    "CREATE TABLE edges (source_id INTEGER, target_id INTEGER, type TEXT, properties TEXT, project TEXT)");
            statement.execute(
                    "INSERT INTO nodes VALUES (1, 'File', 'app.java', 'app.java', 1, '{\"is_test\":false}', 'repo')");
            statement.execute("INSERT INTO nodes VALUES (2, NULL, NULL, NULL, NULL, '{invalid', 'repo')");
            statement.execute("INSERT INTO nodes VALUES (3, 'File', 'missed', '', 0, NULL, 'repo::missed')");
            statement.execute("INSERT INTO nodes VALUES (4, 'File', 'other', '', 0, NULL, 'other')");
            statement.execute("INSERT INTO edges VALUES (1, 2, 'HTTP_CALLS', '{\"method\":\"GET\"}', 'repo')");
            statement.execute("INSERT INTO edges VALUES (2, 1, NULL, '', 'repo')");
            statement.execute("INSERT INTO edges VALUES (4, 1, 'EMITS', NULL, 'other')");
        }
        ParserGraph graph = new ParserGraphReader(new ObjectMapper()).read(database, "repo");
        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.nodes().get(1L).props()).containsEntry("is_test", false);
        assertThat(graph.nodes().get(2L).label()).isEmpty();
        assertThat(graph.nodes().get(2L).name()).isEmpty();
        assertThat(graph.nodes().get(2L).filePath()).isEmpty();
        assertThat(graph.nodes().get(2L).props()).isEmpty();
        assertThat(graph.edges()).hasSize(2);
        assertThat(graph.edges().getFirst().props()).containsEntry("method", "GET");
        assertThat(graph.edges().get(1).type()).isEmpty();
        assertThat(graph.edges().get(1).props()).isEmpty();
        assertThat(graph.unparsedFiles()).containsExactly("missed");

        var batchLogger = mock(IngestionBatchLogService.BatchLogger.class);
        GraphSurfaceAdapter.Result result =
                new GraphSurfaceAdapter(new ObjectMapper()).extract(batch(), database, "repo", batchLogger);
        assertThat(result.unparsedFiles()).isEqualTo(1);
        verify(batchLogger).debug("EXTRACT_SURFACES", "Unparsed files (1): missed");

        assertThatThrownBy(() -> new ParserGraphReader(new ObjectMapper()).read(root.resolve("empty.sqlite"), "repo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Parser graph read failed for repo");
    }

    @Test
    void syftCommandReadsOutputAndReportsFailure(@TempDir Path root) throws Exception {
        ProcessExecutor executor = mock(ProcessExecutor.class);
        IngestionProperties properties = new IngestionProperties();
        properties.getSbom().setSyftPath("syft");
        var adapter = new SyftSurfaceAdapter(executor, properties, new ObjectMapper());
        when(executor.execute(any(), any(), any())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            assertThat(command.get(1)).isEqualTo("dir:" + root.toAbsolutePath());
            Map<String, String> environment = invocation.getArgument(2);
            assertThat(environment).isEqualTo(Map.of("SYFT_CHECK_FOR_APP_UPDATE", "false"));
            Path output = Path.of(command.get(3).substring("cyclonedx-json=".length()));
            Files.writeString(
                    output,
                    "{\"components\":[{\"name\":\"runtime\",\"scope\":\"runtime\",\"purl\":\"pkg:npm/runtime@1\",\"properties\":[{\"name\":\"syft:location:0:path\",\"value\":\"/package.json\"}]}]}");
            return new ProcessExecutor.ProcessResult(0, new byte[0]);
        });
        assertThat(adapter.extract(batch(), root, null)).hasSize(1);
        doReturn(new ProcessExecutor.ProcessResult(9, "bad configuration".getBytes(StandardCharsets.UTF_8)))
                .when(executor)
                .execute(any(), any(), any());
        assertThatThrownBy(() -> adapter.extract(batch(), root, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exit 9 for " + root + ": bad configuration");
        doThrow(new IOException("cannot launch")).when(executor).execute(any(), any(), any());
        assertThatThrownBy(() -> adapter.extract(batch(), root, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not run Syft")
                .hasCauseInstanceOf(IOException.class);
    }
}
