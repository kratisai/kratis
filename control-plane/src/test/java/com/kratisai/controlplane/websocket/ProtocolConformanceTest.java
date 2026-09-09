package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientPayloadType;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.JsonRpcError;
import com.kratisai.controlplane.api.wsdto.JsonRpcErrorCodes;
import com.kratisai.controlplane.api.wsdto.JsonRpcInboundRequest;
import com.kratisai.controlplane.api.wsdto.JsonRpcRequest;
import com.kratisai.controlplane.api.wsdto.JsonRpcResponse;
import com.kratisai.controlplane.api.wsprotocol.Direction;
import com.kratisai.controlplane.api.wsprotocol.MessageKind;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ProtocolConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);

    private static Path protocolDir() {
        Path relative = Paths.get("../protocol");
        if (Files.isDirectory(relative)) {
            return relative;
        }
        Path local = Paths.get("protocol");
        if (Files.isDirectory(local)) {
            return local;
        }
        throw new IllegalStateException(
                "Cannot locate the protocol directory; run tests from the control-plane module");
    }

    @Test
    void closedCollectionMatchesClientProtocolExactly() throws Exception {
        assertMethodSetsMatch(
                protocolDir().resolve("client/openrpc.json"),
                clientMethods(),
                "Every client method in the closed collection must exist in protocol/client/openrpc.json "
                        + "and vice versa");
    }

    @Test
    void closedCollectionMatchesEnvironmentProtocolExactly() throws Exception {
        assertMethodSetsMatch(
                protocolDir().resolve("environment/openrpc.json"),
                environmentMethods(),
                "Every environment method in the closed collection must exist in "
                        + "protocol/environment/openrpc.json and vice versa");
    }

    @Test
    void everyMethodMatchesDirectionKindAndParamsSchema() throws Exception {
        verifyProtocolConformance(
                protocolDir().resolve("client/openrpc.json"),
                clientMethods(),
                protocolDir().resolve("client"));
        verifyProtocolConformance(
                protocolDir().resolve("environment/openrpc.json"),
                environmentMethods(),
                protocolDir().resolve("environment"));
    }

    @Test
    void validFixturesRoundTripThroughTypedParamsRecords() throws Exception {
        verifyValidFixtures(
                protocolDir().resolve("client/openrpc.json"),
                clientMethods(),
                protocolDir().resolve("client"));
        verifyValidFixtures(
                protocolDir().resolve("environment/openrpc.json"),
                environmentMethods(),
                protocolDir().resolve("environment"));
    }

    @Test
    void invalidFixturesAreRejectedByTypedParamsRecords() throws Exception {
        verifyInvalidFixtures(protocolDir().resolve("client"));
        verifyInvalidFixtures(protocolDir().resolve("environment"));
    }

    @Test
    void environmentResultRecordsMatchProtocolResultSchemas() throws Exception {
        Map<String, JsonNode> protocolMethods =
                protocolMethodsByName(protocolDir().resolve("environment/openrpc.json"));
        List<Class<?>> allRecords = new ArrayList<>();
        allRecords.addAll(Arrays.asList(EnvironmentResponsePayload.class.getPermittedSubclasses()));
        allRecords.addAll(Arrays.asList(EnvironmentConnectorResult.class.getPermittedSubclasses()));

        Set<Class<?>> covered = new HashSet<>();
        Set<String> referencedRefs = new HashSet<>();
        for (Map.Entry<String, JsonNode> entry : protocolMethods.entrySet()) {
            JsonNode result = entry.getValue().path("result");
            if (result.isMissingNode()) {
                continue;
            }
            Class<?> record = environmentResultRecord(entry.getKey());
            assertThat(record)
                    .as("Result schema of %s has no matching Java record", entry.getKey())
                    .isNotNull();
            JsonNode schema = resolveResultSchema(result, protocolDir().resolve("environment"));
            verifyRecordAgainstSchema(record, entry.getKey() + " result", schema);
            referencedRefs.add(normalizeRef(result.path("schema").path("$ref").asText()));
            covered.add(record);
        }
        assertThat(covered)
                .as("Every environment result record must be referenced by exactly one protocol result schema")
                .containsExactlyInAnyOrderElementsOf(allRecords);
        assertThat(referencedRefs)
                .as("Every environment result schema file must be referenced by openrpc.json")
                .isEqualTo(listSchemaFiles("environment/schemas/results"));
    }

    @Test
    void clientResultRecordsMatchProtocolResultSchemas() throws Exception {
        Path schemasDir = protocolDir().resolve("client/schemas/results");
        assertThat(Files.isDirectory(schemasDir))
                .as("Client result schemas directory must exist")
                .isTrue();

        List<Class<?>> allRecords = clientPayloadRecords();
        Set<String> coveredTypes = new HashSet<>();
        File[] files = listJsonFiles(schemasDir);
        for (File file : files) {
            String wireType = schemaTypeName(file);
            JsonNode schema = MAPPER.readTree(file);
            Class<?> record = clientResultRecord(ClientPayloadType.fromWire(wireType));
            assertThat(record)
                    .as("Schema %s has no matching Java record", file.getName())
                    .isNotNull();
            assertThat(schema.path("properties").path("type").path("const").asText())
                    .as("Schema %s must pin the type discriminator with const", file.getName())
                    .isEqualTo(wireType);
            if (hasEventComponent(record)) {
                verifyEventPayloadRecord(record, wireType, schema);
            } else {
                verifyRecordAgainstSchema(record, wireType, schema);
            }
            coveredTypes.add(wireType);
        }
        assertThat(coveredTypes)
                .as("Every ClientPayload record must have exactly one protocol result schema "
                        + "(one schema file per closed type discriminator)")
                .hasSize(allRecords.size());
    }

    @Test
    void clientOpenRpcReferencesEveryResultSchema() throws Exception {
        JsonNode root =
                MAPPER.readTree(protocolDir().resolve("client/openrpc.json").toFile());
        Set<String> referenced = new HashSet<>();
        root.path("methods")
                .forEach(method -> collectSchemaRefs(method.path("result").path("schema"), referenced));
        root.path("x-kratis-notifications")
                .forEach(notification -> collectSchemaRefs(notification.path("schema"), referenced));
        assertThat(referenced)
                .as("Every client result schema must be referenced by openrpc.json "
                        + "(method result or x-kratis-notifications)")
                .isEqualTo(listSchemaFiles("client/schemas/results"));
    }

    @Test
    void validResultFixturesRoundTripThroughTypedResultRecords() throws Exception {
        Path envValid = protocolDir().resolve("environment/examples/results/valid");
        for (File file : listJsonFiles(envValid)) {
            Class<?> record = environmentResultRecordByFixtureName(fileNameWithoutExtension(file));
            JsonNode result = MAPPER.readTree(file);
            Object parsed = MAPPER.treeToValue(result, record);
            assertThat(jsonDeepEquals(MAPPER.valueToTree(parsed), result))
                    .as(
                            "Environment result fixture %s must round-trip through %s",
                            file.getName(), record.getSimpleName())
                    .isTrue();
        }

        Path clientValid = protocolDir().resolve("client/examples/results/valid");
        Set<String> coveredTypes = new HashSet<>();
        for (File file : listJsonFiles(clientValid)) {
            JsonNode result = MAPPER.readTree(file);
            String wireType = result.path("type").asText();
            Class<?> record = clientResultRecord(ClientPayloadType.fromWire(wireType));
            coveredTypes.add(wireType);
            JsonNode schema = resultSchema(wireType);
            if (hasEventComponent(record)) {
                assertThat(validateResultAgainstSchema(result, schema))
                        .as("Client result fixture %s must match schema %s", file.getName(), wireType)
                        .isEmpty();
            } else {
                Object parsed = MAPPER.treeToValue(result, record);
                assertThat(jsonDeepEquals(MAPPER.valueToTree(parsed), result))
                        .as(
                                "Client result fixture %s must round-trip through %s",
                                file.getName(), record.getSimpleName())
                        .isTrue();
            }
        }
        assertThat(coveredTypes)
                .as("Every client result type must have at least one valid example fixture")
                .hasSize(ClientPayloadType.values().length);
    }

    @Test
    void invalidResultFixturesAreRejected() throws Exception {
        for (File file : listJsonFiles(protocolDir().resolve("environment/examples/results/invalid"))) {
            Class<?> record = environmentResultRecordByFixtureName(fileNameWithoutExtension(file));
            assertThatThrownBy(() -> MAPPER.treeToValue(MAPPER.readTree(file), record))
                    .as("Invalid environment result fixture %s must be rejected", file.getName())
                    .isInstanceOf(Exception.class);
        }

        for (File file : listJsonFiles(protocolDir().resolve("client/examples/results/invalid"))) {
            JsonNode result = MAPPER.readTree(file);
            String wireType = result.path("type").asText();
            ClientPayloadType payloadType;
            try {
                payloadType = ClientPayloadType.fromWire(wireType);
            } catch (IllegalArgumentException e) {
                assertThat(e.getMessage())
                        .as("Invalid client result fixture %s with unknown type must be rejected", file.getName())
                        .contains("Unknown client payload type");
                continue;
            }
            Class<?> record = clientResultRecord(payloadType);
            JsonNode schema = resultSchema(wireType);
            if (hasEventComponent(record)) {
                assertThat(validateResultAgainstSchema(result, schema))
                        .as("Invalid client result fixture %s must be rejected by schema %s", file.getName(), wireType)
                        .isNotEmpty();
            } else {
                assertThatThrownBy(() -> MAPPER.treeToValue(result, record))
                        .as(
                                "Invalid client result fixture %s must be rejected by %s",
                                file.getName(), record.getSimpleName())
                        .isInstanceOf(Exception.class);
            }
        }
    }

    @Test
    void envelopeRecordsMatchCommonEnvelopeSchemas() throws Exception {
        Path common = protocolDir().resolve("common");
        assertEnvelope(JsonRpcRequest.class, common.resolve("json-rpc-request.schema.json"), Set.of("params", "id"));
        assertEnvelope(
                JsonRpcInboundRequest.class, common.resolve("json-rpc-request.schema.json"), Set.of("params", "id"));
        assertEnvelope(
                JsonRpcResponse.class,
                common.resolve("json-rpc-response.schema.json"),
                Set.of("result", "error", "id"));
        assertEnvelope(JsonRpcError.class, common.resolve("json-rpc-error.schema.json"), Set.of("data"));
    }

    @Test
    void errorCodesMatchProtocolRegistry() throws Exception {
        JsonNode registry =
                MAPPER.readTree(protocolDir().resolve("common/error-codes.json").toFile());
        Map<String, Integer> registered = new HashMap<>();
        registry.path("standard")
                .properties()
                .forEach(e -> registered.put(e.getKey(), e.getValue().asInt()));
        registry.path("kratis")
                .properties()
                .forEach(e -> registered.put(e.getKey(), e.getValue().asInt()));

        Set<String> rangeMarkers = Set.of("SERVER_ERROR_START", "SERVER_ERROR_END");
        for (Field field : JsonRpcErrorCodes.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || rangeMarkers.contains(field.getName())) {
                continue;
            }
            assertThat(registered)
                    .as("Constant %s must be registered in protocol/common/error-codes.json", field.getName())
                    .containsEntry(field.getName(), field.getInt(null));
        }
        assertThat(registered)
                .as("Every protocol error code must have a JsonRpcErrorCodes constant")
                .hasSize(JsonRpcErrorCodes.class.getDeclaredFields().length - rangeMarkers.size());
    }

    @Test
    void errorFactoriesUseRegisteredCodes() throws Exception {
        Set<Integer> registered = new HashSet<>();
        JsonNode registry =
                MAPPER.readTree(protocolDir().resolve("common/error-codes.json").toFile());
        registry.path("standard")
                .properties()
                .forEach(e -> registered.add(e.getValue().asInt()));
        registry.path("kratis")
                .properties()
                .forEach(e -> registered.add(e.getValue().asInt()));

        List<JsonRpcError> errors = List.of(
                JsonRpcError.InternalError(new RuntimeException("boom")),
                JsonRpcError.MethodNotFound("m"),
                JsonRpcError.InvalidParams("p"),
                JsonRpcError.InvalidToken(),
                JsonRpcError.NotAuthenticated(),
                JsonRpcError.NotAuthorised("d"));
        for (JsonRpcError error : errors) {
            assertThat(registered)
                    .as("JsonRpcError factory produced an unregistered code %s (%s)", error.code(), error.message())
                    .contains(error.code());
        }
    }

    private static void assertMethodSetsMatch(Path openrpcFile, List<Class<?>> methods, String description)
            throws Exception {
        List<String> protocolNames = protocolMethodNames(openrpcFile);
        List<String> codeNames =
                methods.stream().map(ProtocolConformanceTest::methodName).toList();
        assertThat(codeNames).as(description).containsExactlyInAnyOrderElementsOf(protocolNames);
    }

    private static void verifyProtocolConformance(Path openrpcFile, List<Class<?>> methods, Path protocolDir)
            throws Exception {
        Map<String, JsonNode> protocolMethods = protocolMethodsByName(openrpcFile);
        for (Class<?> payload : methods) {
            String method = methodName(payload);
            JsonNode protocolMethod = protocolMethods.get(method);
            assertThat(protocolMethod)
                    .as("Method %s must be defined in %s", method, openrpcFile)
                    .isNotNull();

            assertThat(expectedKind(payload))
                    .as("Message kind of %s in %s", method, openrpcFile)
                    .isEqualTo(MessageKind.fromWire(
                            protocolMethod.path("x-kratis-message-kind").asText()));
            assertThat(expectedDirection(payload))
                    .as("Direction of %s in %s", method, openrpcFile)
                    .isEqualTo(Direction.fromWire(
                            protocolMethod.path("x-kratis-direction").asText()));

            JsonNode params = protocolMethod.path("params");
            if (hasNoParams(payload)) {
                assertThat(params)
                        .as("Method %s declares no params in %s", method, openrpcFile)
                        .isEmpty();
                continue;
            }
            verifyRecordAgainstSchema(payload, method, resolveParamsSchema(params, protocolDir));
        }
    }

    private static MessageKind expectedKind(Class<?> payload) {
        if (EnvironmentRpcPayload.InboundNotificationPayload.class.isAssignableFrom(payload)
                || EnvironmentRpcPayload.OutboundNotificationPayload.class.isAssignableFrom(payload)) {
            return MessageKind.NOTIFICATION;
        }
        return MessageKind.REQUEST;
    }

    private static Direction expectedDirection(Class<?> payload) {
        if (ClientRpcPayload.class.isAssignableFrom(payload)) {
            return Direction.WEB_TO_CONTROL_PLANE;
        }
        if (EnvironmentRpcPayload.OutboundRequestPayload.class.isAssignableFrom(payload)
                || EnvironmentRpcPayload.OutboundNotificationPayload.class.isAssignableFrom(payload)) {
            return Direction.CONTROL_PLANE_TO_CONNECTOR;
        }
        return Direction.CONNECTOR_TO_CONTROL_PLANE;
    }

    private static JsonNode resolveParamsSchema(JsonNode params, Path protocolDir) throws IOException {
        assertThat(params)
                .as("Methods with parameters must declare a params schema")
                .isNotEmpty();
        String ref = params.get(0).path("schema").path("$ref").asText();
        Path schemaFile = protocolDir.resolve(ref).normalize();
        assertThat(Files.isRegularFile(schemaFile))
                .as("Params schema %s must exist", schemaFile)
                .isTrue();
        return MAPPER.readTree(schemaFile.toFile());
    }

    private static JsonNode resolveResultSchema(JsonNode result, Path protocolDir) throws IOException {
        String ref = result.path("schema").path("$ref").asText();
        Path schemaFile = protocolDir.resolve(ref).normalize();
        assertThat(Files.isRegularFile(schemaFile))
                .as("Result schema %s must exist", schemaFile)
                .isTrue();
        return MAPPER.readTree(schemaFile.toFile());
    }

    private static JsonNode resultSchema(String wireType) throws IOException {
        return MAPPER.readTree(protocolDir()
                .resolve("client/schemas/results/" + wireType + ".schema.json")
                .toFile());
    }

    /**
     * Required-field enforcement works through the records' compact constructors: Jackson nulls
     * missing components, so {@code requireNonNull} is what rejects them.
     */
    private static void verifyRecordAgainstSchema(Class<?> payload, String label, JsonNode schema) {
        assertThat(payload.isRecord())
                .as("Payload of %s must be a Java record", label)
                .isTrue();

        Set<String> schemaProperties = propertyNames(schema);
        Set<String> recordProperties = recordJsonNames(payload);
        assertThat(recordProperties)
                .as("Record components of %s must exactly match its JSON Schema properties", label)
                .isEqualTo(schemaProperties);

        List<String> required = requiredNames(schema);

        ObjectNode sample = buildSample(schema);
        assertThatCode(() -> MAPPER.treeToValue(sample, payload))
                .as("A payload object containing every property of %s must deserialize", label)
                .doesNotThrowAnyException();

        for (String property : required) {
            RecordComponent component = componentNamed(payload, property);
            if (component == null || component.getType().isPrimitive()) {
                continue;
            }
            ObjectNode missing = sample.deepCopy();
            missing.remove(property);
            assertThatThrownBy(() -> MAPPER.treeToValue(missing, payload))
                    .as("Required property %s of %s must be enforced", property, label)
                    .isInstanceOf(Exception.class);
        }

        schema.path("properties").properties().forEach(entry -> {
            JsonNode enumValues = entry.getValue().path("enum");
            if (enumValues.isArray() && !enumValues.isEmpty()) {
                ObjectNode invalid = sample.deepCopy();
                invalid.put(entry.getKey(), "not-a-valid-enum-value");
                assertThatThrownBy(() -> MAPPER.treeToValue(invalid, payload))
                        .as("Enum on %s.%s must be enforced", label, entry.getKey())
                        .isInstanceOf(Exception.class);
            }
        });

        if (!schema.path("additionalProperties").asBoolean(true)) {
            ObjectNode extra = sample.deepCopy();
            extra.put("unexpectedExtraProperty", "boom");
            assertThatThrownBy(() -> MAPPER.treeToValue(extra, payload))
                    .as("Unknown properties for %s must be rejected", label)
                    .isInstanceOf(Exception.class);
        }
    }

    private static void verifyEventPayloadRecord(Class<?> record, String wireType, JsonNode schema) {
        Set<String> schemaProperties = propertyNames(schema);
        Set<String> recordProperties = recordJsonNames(record);
        assertThat(recordProperties)
                .as("Record components of %s must exactly match its JSON Schema properties", wireType)
                .isEqualTo(schemaProperties);

        JsonNode anyOf = schema.path("properties").path("event").path("anyOf");
        assertThat(anyOf.isArray() && !anyOf.isEmpty())
                .as("Schema %s must describe the event variants with anyOf", wireType)
                .isTrue();

        RecordComponent eventComponent = componentNamed(record, "event");
        assertThat(eventComponent)
                .as("Payload %s must declare an event component", wireType)
                .isNotNull();
        List<Class<?>> variants = Arrays.asList(eventComponent.getType().getPermittedSubclasses());
        List<JsonNode> branches = new ArrayList<>();
        anyOf.forEach(branches::add);

        for (Class<?> variant : variants) {
            Set<String> variantProps = recordJsonNames(variant);
            assertThat(branches.stream().anyMatch(branch -> variantProps.equals(propertyNames(branch))))
                    .as(
                            "Sealed event variant %s of %s must match a schema anyOf branch",
                            variant.getSimpleName(), wireType)
                    .isTrue();
        }
        for (JsonNode branch : branches) {
            assertThat(variants.stream()
                            .anyMatch(variant -> recordJsonNames(variant).equals(propertyNames(branch))))
                    .as("Schema anyOf branch of %s must match a sealed event variant", wireType)
                    .isTrue();
        }
    }

    private static List<String> validateResultAgainstSchema(JsonNode payload, JsonNode schema) {
        List<String> violations = new ArrayList<>();
        Set<String> schemaProperties = propertyNames(schema);
        payload.fieldNames().forEachRemaining(name -> {
            if (!schemaProperties.contains(name)) {
                violations.add("unknown field: " + name);
            }
        });
        for (String required : requiredNames(schema)) {
            if (!payload.has(required) || payload.path(required).isNull()) {
                violations.add("missing required field: " + required);
            }
        }
        schema.path("properties").properties().forEach(entry -> {
            JsonNode value = payload.get(entry.getKey());
            if (value == null) {
                return;
            }
            JsonNode constValue = entry.getValue().path("const");
            if (!constValue.isMissingNode() && !value.equals(constValue)) {
                violations.add("const mismatch on " + entry.getKey() + ": " + value);
            }
            JsonNode enumValues = entry.getValue().path("enum");
            if (enumValues.isArray() && !enumValues.isEmpty() && !containsNode(enumValues, value)) {
                violations.add("invalid enum value for " + entry.getKey() + ": " + value);
            }
        });
        return violations;
    }

    private static boolean containsNode(JsonNode array, JsonNode value) {
        for (JsonNode element : array) {
            if (element.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode buildSample(JsonNode schema) {
        ObjectNode sample = MAPPER.createObjectNode();
        schema.path("properties").properties().forEach(p -> sample.set(p.getKey(), sampleValue(p.getValue())));
        return sample;
    }

    private static JsonNode sampleValue(JsonNode propertySchema) {
        JsonNode constValue = propertySchema.path("const");
        if (!constValue.isMissingNode()) {
            return constValue;
        }
        JsonNode enumValues = propertySchema.path("enum");
        if (enumValues.isArray() && !enumValues.isEmpty()) {
            return enumValues.get(0);
        }
        if ("uuid".equals(propertySchema.path("format").asText())) {
            return TextNode.valueOf("00000000-0000-0000-0000-000000000000");
        }
        return switch (propertySchema.path("type").asText()) {
            case "string" -> TextNode.valueOf("sample-value");
            case "integer" -> IntNode.valueOf(1);
            case "number" -> DoubleNode.valueOf(1.5);
            case "boolean" -> BooleanNode.TRUE;
            case "array" -> MAPPER.createArrayNode();
            case "object" -> MAPPER.createObjectNode();
            default -> NullNode.getInstance();
        };
    }

    private static Set<String> propertyNames(JsonNode schema) {
        Set<String> names = new LinkedHashSet<>();
        schema.path("properties").properties().forEach(p -> names.add(p.getKey()));
        return names;
    }

    private static List<String> requiredNames(JsonNode schema) {
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(r -> required.add(r.asText()));
        return required;
    }

    private static Set<String> recordJsonNames(Class<?> recordClass) {
        Set<String> names = new LinkedHashSet<>();
        for (RecordComponent component : recordClass.getRecordComponents()) {
            JsonProperty property = component.getAnnotation(JsonProperty.class);
            names.add(property != null ? property.value() : component.getName());
        }
        return names;
    }

    private static RecordComponent componentNamed(Class<?> recordClass, String name) {
        for (RecordComponent component : recordClass.getRecordComponents()) {
            if (component.getName().equals(name)) {
                return component;
            }
        }
        return null;
    }

    private static boolean hasEventComponent(Class<?> record) {
        RecordComponent event = componentNamed(record, "event");
        return event != null && event.getType().isSealed();
    }

    private static void verifyValidFixtures(Path openrpcFile, List<Class<?>> methods, Path protocolDir)
            throws Exception {
        Map<String, Class<?>> byName = methodsByName(methods);
        Set<String> covered = new HashSet<>();
        File[] files = listJsonFiles(protocolDir.resolve("examples/valid"));
        for (File file : files) {
            JsonRpcInboundRequest request = MAPPER.readValue(file, JsonRpcInboundRequest.class);
            Class<?> payload = byName.get(request.method());
            assertThat(payload)
                    .as("Valid fixture %s uses a method outside the closed collection", file.getName())
                    .isNotNull();
            covered.add(request.method());

            if (hasNoParams(payload)) {
                assertThat(request.params())
                        .as("Fixture %s for a parameter-less method must omit params", file.getName())
                        .isNull();
                continue;
            }
            Object params = MAPPER.treeToValue(request.params(), payload);
            JsonNode roundTripped = MAPPER.valueToTree(params);
            assertThat(jsonDeepEquals(roundTripped, request.params()))
                    .as("Fixture %s params must round-trip through %s", file.getName(), payload.getSimpleName())
                    .isTrue();
        }
        Set<String> protocolNames = new HashSet<>(protocolMethodNames(openrpcFile));
        assertThat(covered)
                .as("Every method in %s must have at least one valid example fixture", openrpcFile)
                .isEqualTo(protocolNames);
    }

    private static void verifyInvalidFixtures(Path protocolDir) throws Exception {
        File[] files = listJsonFiles(protocolDir.resolve("examples/invalid"));
        for (File file : files) {
            JsonRpcInboundRequest request = MAPPER.readValue(file, JsonRpcInboundRequest.class);
            Class<?> payload = findByMethodName(request.method());
            assertThat(payload)
                    .as("Invalid fixture %s uses a method outside the closed collection", file.getName())
                    .isNotNull();
            if (hasNoParams(payload)) {
                continue;
            }
            assertThatThrownBy(() -> MAPPER.treeToValue(request.params(), payload))
                    .as("Invalid fixture %s must be rejected by %s", file.getName(), payload.getSimpleName())
                    .isInstanceOf(Exception.class);
        }
    }

    private static void assertEnvelope(Class<?> record, Path schemaFile, Set<String> omittable) throws IOException {
        JsonNode schema = MAPPER.readTree(schemaFile.toFile());
        Set<String> schemaProperties = propertyNames(schema);
        Set<String> recordProperties = recordJsonNames(record);
        assertThat(recordProperties)
                .as("Components of %s must exactly match %s properties", record.getSimpleName(), schemaFile)
                .isEqualTo(schemaProperties);

        Set<String> alwaysPresent = new LinkedHashSet<>(recordProperties);
        alwaysPresent.removeAll(omittable);
        assertThat(new HashSet<>(requiredNames(schema)))
                .as("Required of %s must be the always-serialized components", record.getSimpleName())
                .isEqualTo(alwaysPresent);
    }

    private static void collectSchemaRefs(JsonNode schema, Set<String> refs) {
        if (schema == null || schema.isMissingNode()) {
            return;
        }
        String ref = schema.path("$ref").asText("");
        if (!ref.isEmpty()) {
            refs.add(normalizeRef(ref));
        }
        schema.path("oneOf").forEach(child -> collectSchemaRefs(child, refs));
        schema.path("anyOf").forEach(child -> collectSchemaRefs(child, refs));
    }

    private static String normalizeRef(String ref) {
        Path normalized = Paths.get(ref.replaceFirst("^\\./", ""));
        Path fileName = normalized.getFileName();
        return fileName != null ? fileName.toString() : normalized.toString();
    }

    private static Set<String> listSchemaFiles(String relativeDir) {
        File[] files = listJsonFiles(protocolDir().resolve(relativeDir));
        Set<String> names = new LinkedHashSet<>();
        for (File file : files) {
            names.add(file.getName());
        }
        return names;
    }

    private static File[] listJsonFiles(Path dir) {
        assertThat(Files.isDirectory(dir))
                .as("Examples directory %s must exist", dir)
                .isTrue();
        File[] files = dir.toFile().listFiles((d, name) -> name.endsWith(".json"));
        assertThat(files).isNotNull();
        return files;
    }

    private static Map<String, JsonNode> protocolMethodsByName(Path openrpcFile) throws IOException {
        JsonNode root = MAPPER.readTree(openrpcFile.toFile());
        Map<String, JsonNode> methods = new TreeMap<>();
        root.path("methods").forEach(m -> methods.put(m.path("name").asText(), m));
        return methods;
    }

    private static List<String> protocolMethodNames(Path openrpcFile) throws IOException {
        return new ArrayList<>(protocolMethodsByName(openrpcFile).keySet());
    }

    private static List<Class<?>> clientMethods() {
        return Arrays.asList(ClientRpcPayload.class.getPermittedSubclasses());
    }

    private static List<Class<?>> environmentMethods() {
        List<Class<?>> result = new ArrayList<>();
        for (Class<?> sub : EnvironmentRpcPayload.class.getPermittedSubclasses()) {
            result.addAll(Arrays.asList(sub.getPermittedSubclasses()));
        }
        return result;
    }

    private static Map<String, Class<?>> methodsByName(List<Class<?>> methods) {
        return methods.stream()
                .collect(Collectors.toMap(ProtocolConformanceTest::methodName, m -> m, (a, b) -> a, HashMap::new));
    }

    private static Class<?> findByMethodName(String methodName) {
        for (Class<?> payload : clientMethods()) {
            if (methodName(payload).equals(methodName)) {
                return payload;
            }
        }
        for (Class<?> payload : environmentMethods()) {
            if (methodName(payload).equals(methodName)) {
                return payload;
            }
        }
        return null;
    }

    private static String methodName(Class<?> payload) {
        try {
            return (String) payload.getDeclaredField("METHOD").get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Payload record " + payload.getName() + " must declare a static METHOD constant", e);
        }
    }

    private static boolean hasNoParams(Class<?> payload) {
        return payload.getRecordComponents().length == 0;
    }

    private static String fileNameWithoutExtension(File file) {
        String name = file.getName();
        return name.substring(0, name.length() - ".json".length());
    }

    private static String schemaTypeName(File file) {
        String name = file.getName();
        return name.substring(0, name.length() - ".schema.json".length());
    }

    private static List<Class<?>> clientPayloadRecords() {
        return Arrays.stream(ClientPayload.class.getPermittedSubclasses())
                .filter(Class::isRecord)
                .toList();
    }

    private static Class<?> environmentResultRecord(String method) {
        return switch (method) {
            case "env.register" -> EnvironmentResponsePayload.EnvironmentRegisterResult.class;
            case "env.heartbeat" -> EnvironmentResponsePayload.EnvironmentHeartbeatResult.class;
            case "env.hitl_request" -> EnvironmentResponsePayload.HitlResult.class;
            case "env.git_token" -> EnvironmentResponsePayload.GitTokenResult.class;
            case "env.exec" -> EnvironmentConnectorResult.Exec.class;
            case "env.launch_acp_agent" -> EnvironmentConnectorResult.LaunchAcpAgent.class;
            case "env.acp_prompt" -> EnvironmentConnectorResult.AcpPrompt.class;
            case "env.terminate" -> EnvironmentConnectorResult.Terminate.class;
            case "env.registerGitAuth" -> EnvironmentConnectorResult.RegisterGitAuth.class;
            case "env.git_diff_summary" -> EnvironmentConnectorResult.GitDiffSummary.class;
            case "env.git_file_diff" -> EnvironmentConnectorResult.GitFileDiff.class;
            case "env.read_file_slice" -> EnvironmentConnectorResult.ReadFileSlice.class;
            case "env.git_push" -> EnvironmentConnectorResult.GitPush.class;
            default -> null;
        };
    }

    private static Class<?> environmentResultRecordByFixtureName(String name) {
        return switch (name) {
            case "register" -> EnvironmentResponsePayload.EnvironmentRegisterResult.class;
            case "heartbeat" -> EnvironmentResponsePayload.EnvironmentHeartbeatResult.class;
            case "hitl_request" -> EnvironmentResponsePayload.HitlResult.class;
            case "git_token" -> EnvironmentResponsePayload.GitTokenResult.class;
            case "exec" -> EnvironmentConnectorResult.Exec.class;
            case "launch_acp_agent" -> EnvironmentConnectorResult.LaunchAcpAgent.class;
            case "acp_prompt" -> EnvironmentConnectorResult.AcpPrompt.class;
            case "terminate" -> EnvironmentConnectorResult.Terminate.class;
            case "register_git_auth" -> EnvironmentConnectorResult.RegisterGitAuth.class;
            case "git_diff_summary" -> EnvironmentConnectorResult.GitDiffSummary.class;
            case "git_file_diff" -> EnvironmentConnectorResult.GitFileDiff.class;
            case "read_file_slice" -> EnvironmentConnectorResult.ReadFileSlice.class;
            case "git_push" -> EnvironmentConnectorResult.GitPush.class;
            default -> throw new AssertionError("Unexpected environment result fixture: " + name);
        };
    }

    private static Class<? extends ClientPayload> clientResultRecord(ClientPayloadType type) {
        return switch (type) {
            case AUTH -> ClientPayload.AuthResult.class;
            case PING -> ClientPayload.PingResult.class;
            case SUBSCRIPTION -> ClientPayload.SubscriptionResult.class;
            case CHAT_SUBSCRIPTION -> ClientPayload.ChatSubscriptionResult.class;
            case MESSAGE -> ClientPayload.MessageResult.class;
            case MESSAGE_CHUNK -> ClientPayload.MessageChunkResult.class;
            case TELEMETRY -> ClientPayload.TelemetryResult.class;
            case COMPLETE -> ClientPayload.CompleteResult.class;
            case CHAT_ERROR -> ClientPayload.ChatErrorResult.class;
            case CANVAS -> ClientPayload.CanvasResult.class;
            case INGESTION -> ClientPayload.IngestionResult.class;
            case TEAM_ENTITY_CHANGED -> ClientPayload.TeamEntityChangedResult.class;
            case USER_ENTITY_CHANGED -> ClientPayload.UserEntityChangedResult.class;
            case EXECUTION_HITL_REQUIRED -> ClientPayload.ExecutionHitlRequiredResult.class;
            case EXECUTION_HITL_RESOLVED -> ClientPayload.ExecutionHitlResolvedResult.class;
            case EXECUTION_OUTPUT -> ClientPayload.ExecutionOutputResult.class;
            case EXECUTION_COMPLETE -> ClientPayload.ExecutionCompleteResult.class;
            case EXECUTION_STATUS_CHANGED -> ClientPayload.ExecutionStatusChangedResult.class;
            case EXECUTION_ACTIVITY -> ClientPayload.ExecutionActivityResult.class;
            case EXECUTION_REPLAY_COMPLETE -> ClientPayload.ExecutionReplayCompleteResult.class;
            case EXECUTION_ACP_INITIALIZED -> ClientPayload.ExecutionAcpInitializedResult.class;
        };
    }

    private static boolean jsonDeepEquals(JsonNode left, JsonNode right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().equals(right.decimalValue());
        }
        if (left.isObject() && right.isObject()) {
            if (left.size() != right.size()) {
                return false;
            }
            return left.propertyStream().allMatch(field -> jsonDeepEquals(field.getValue(), right.get(field.getKey())));
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int i = 0; i < left.size(); i++) {
                if (!jsonDeepEquals(left.get(i), right.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return left.equals(right);
    }
}
