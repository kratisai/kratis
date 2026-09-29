package com.kratisai.controlplane.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.api.wsdto.ClientPayloadType;
import com.kratisai.controlplane.api.wsdto.ClientRpcPayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentResponsePayload;
import com.kratisai.controlplane.api.wsdto.EnvironmentResultType;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.RpcPayload;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArchUnitProtocolTest {

    @Test
    void verifyPayloadsAreRecordsWithTypedFields() {
        Class<?>[] payloadClasses = concat(ClientRpcPayload.class.getPermittedSubclasses(), environmentPayloads());

        for (Class<?> clazz : payloadClasses) {
            assertThat(clazz.isRecord())
                    .as(clazz.getName() + " must be a Java Record")
                    .isTrue();
            for (RecordComponent comp : clazz.getRecordComponents()) {
                assertThat(comp.getType())
                        .as(clazz.getName() + "." + comp.getName() + " must not be Object")
                        .isNotEqualTo(Object.class);
            }
            assertThat(RpcPayload.class.isAssignableFrom(clazz))
                    .as(clazz.getName() + " must be part of the closed RpcPayload hierarchy")
                    .isTrue();
        }
    }

    @Test
    void verifyClosedPayloadCollectionContainsOnlyRecords() {
        assertThat(ClientRpcPayload.class.isSealed())
                .as("ClientRpcPayload must be a sealed (closed) collection")
                .isTrue();
        assertThat(EnvironmentRpcPayload.class.isSealed())
                .as("EnvironmentRpcPayload must be a sealed (closed) collection")
                .isTrue();
        for (Class<?> permitted : ClientRpcPayload.class.getPermittedSubclasses()) {
            assertThat(permitted.isRecord())
                    .as("Client protocol payload " + permitted.getSimpleName() + " must be a record")
                    .isTrue();
        }
        for (Class<?> permitted : environmentPayloads()) {
            assertThat(permitted.isRecord())
                    .as("Environment protocol payload " + permitted.getSimpleName() + " must be a record")
                    .isTrue();
        }
    }

    @Test
    void verifyEveryPayloadDeclaresWireMethodConstant() {
        for (Class<?> permitted : ClientRpcPayload.class.getPermittedSubclasses()) {
            assertThat(declaredMethodConstant(permitted))
                    .as("Payload " + permitted.getSimpleName() + " must declare a static METHOD constant")
                    .isNotBlank();
        }
        for (Class<?> permitted : environmentPayloads()) {
            assertThat(declaredMethodConstant(permitted))
                    .as("Payload " + permitted.getSimpleName() + " must declare a static METHOD constant")
                    .isNotBlank();
        }
    }

    @Test
    void verifyResultPayloadsAreRecordsWithTypedFields() {
        for (Class<?> clazz : resultPayloadClasses()) {
            assertThat(clazz.isRecord())
                    .as(clazz.getName() + " must be a Java Record")
                    .isTrue();
            for (RecordComponent comp : clazz.getRecordComponents()) {
                assertThat(comp.getType())
                        .as(clazz.getName() + "." + comp.getName() + " must not be Object")
                        .isNotEqualTo(Object.class);
                if (comp.getName().equals("type")) {
                    assertThat(comp.getType())
                            .as(clazz.getName() + ".type must be a closed discriminator enum, not a raw String")
                            .isNotEqualTo(String.class);
                }
            }
        }
    }

    @Test
    void verifyClientPayloadDiscriminatorsAreClosedEnum() {
        for (Class<?> clazz : clientPayloadRecords()) {
            RecordComponent type = typeComponent(clazz);
            assertThat(type)
                    .as(clazz.getSimpleName() + " must declare a type discriminator component")
                    .isNotNull();
            assertThat(type.getType())
                    .as(clazz.getSimpleName() + " type discriminator must be the closed "
                            + ClientPayloadType.class.getSimpleName())
                    .isEqualTo(ClientPayloadType.class);
        }
    }

    @Test
    void verifyEnvironmentResponseDiscriminatorsAreClosedEnum() {
        for (Class<?> clazz : EnvironmentResponsePayload.class.getPermittedSubclasses()) {
            RecordComponent type = typeComponent(clazz);
            if (type == null) {
                continue; // e.g. RequestPermissionResult has no wire type
            }
            assertThat(type.getType())
                    .as(clazz.getSimpleName() + " type discriminator must be the closed "
                            + EnvironmentResultType.class.getSimpleName())
                    .isEqualTo(EnvironmentResultType.class);
        }
    }

    @Test
    void verifyClientPayloadTypeValuesAreUniquePerRecord() {
        assertThat(clientPayloadRecords()).hasSize(ClientPayloadType.values().length);
        for (ClientPayloadType value : ClientPayloadType.values()) {
            assertThat(value.getWire()).isNotBlank();
        }
    }

    private static List<Class<?>> clientPayloadRecords() {
        return Arrays.stream(ClientPayload.class.getPermittedSubclasses())
                .filter(Class::isRecord)
                .toList();
    }

    private static Class<?>[] resultPayloadClasses() {
        List<Class<?>> result = new ArrayList<>(clientPayloadRecords());
        result.addAll(Arrays.asList(EnvironmentResponsePayload.class.getPermittedSubclasses()));
        result.addAll(Arrays.asList(EnvironmentConnectorResult.class.getPermittedSubclasses()));
        return result.toArray(new Class<?>[0]);
    }

    private static RecordComponent typeComponent(Class<?> clazz) {
        for (RecordComponent comp : clazz.getRecordComponents()) {
            if (comp.getName().equals("type")) {
                return comp;
            }
        }
        return null;
    }

    private static String declaredMethodConstant(Class<?> type) {
        try {
            return (String) type.getDeclaredField("METHOD").get(null);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Class<?>[] environmentPayloads() {
        List<Class<?>> result = new ArrayList<>();
        for (Class<?> sub : EnvironmentRpcPayload.class.getPermittedSubclasses()) {
            result.addAll(java.util.Arrays.asList(sub.getPermittedSubclasses()));
        }
        return result.toArray(new Class<?>[0]);
    }

    private static Class<?>[] concat(Class<?>[] first, Class<?>[] second) {
        Class<?>[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
