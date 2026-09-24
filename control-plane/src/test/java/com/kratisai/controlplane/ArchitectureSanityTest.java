package com.kratisai.controlplane;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.kratisai.controlplane.agentloop.KratisTool;
import com.kratisai.controlplane.config.AsyncConfig;
import com.kratisai.controlplane.ingestion.research.DimensionResearchService;
import com.kratisai.controlplane.service.ClientRealtimeEventListeners;
import com.kratisai.controlplane.service.EnvironmentRpcClient;
import com.kratisai.controlplane.service.VirtualKeyService;
import com.kratisai.controlplane.service.WebSocketDispatch;
import com.kratisai.controlplane.websocket.client.ClientRpcHandler;
import com.kratisai.controlplane.websocket.client.ClientWebSocketHandler;
import com.kratisai.controlplane.websocket.environment.EnvironmentRpcHandler;
import com.kratisai.controlplane.websocket.environment.EnvironmentWebSocketHandler;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.GeneralCodingRules;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

@AnalyzeClasses(
        packages = "com.kratisai.controlplane",
        importOptions = {
            ImportOption.DoNotIncludeTests.class,
            ImportOption.DoNotIncludeJars.class,
            ArchitectureSanityTest.DoNotIncludeAotGeneratedClasses.class
        })
public class ArchitectureSanityTest {

    static final class DoNotIncludeAotGeneratedClasses implements ImportOption {
        @Override
        public boolean includes(com.tngtech.archunit.core.importer.Location location) {
            return !location.contains("__BeanDefinitions")
                    && !location.contains("__BeanFactoryRegistrations")
                    && !location.contains("__AotProcessor");
        }
    }

    @ArchTest
    public static final ArchRule NO_FIELD_INJECTION = GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    @ArchTest
    public static final ArchRule NO_STANDARD_STREAMS = GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    public static final ArchRule NO_GENERIC_EXCEPTIONS = noClasses()
            .should(new ArchCondition<JavaClass>("throw generic exceptions") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    for (JavaConstructorCall call : javaClass.getConstructorCallsFromSelf()) {
                        String targetName = call.getTargetOwner().getName();
                        if (targetName.equals(Throwable.class.getName())
                                || targetName.equals(Exception.class.getName())) {
                            String message = String.format(
                                    "Method %s calls constructor of generic exception %s",
                                    call.getOrigin().getFullName(), targetName);
                            events.add(SimpleConditionEvent.violated(javaClass, message));
                        }
                    }
                }
            })
            .because("Classes should throw specific subclass exceptions, not raw Exception or Throwable.");

    /**
     * {@code @Lazy} on a constructor parameter makes Spring generate a CGLIB lazy-resolution proxy
     * at run time. GraalVM's build-time generated subclass cannot reconcile that proxy's callbacks,
     * so the native image aborts during context refresh with a {@code ClassCastException}. Break the
     * cycle with an {@code ObjectProvider} or restructure the dependency instead.
     */
    @ArchTest
    public static final ArchRule NO_LAZY_CONSTRUCTOR_INJECTION = noClasses()
            .should(new ArchCondition<JavaClass>("not use @Lazy on constructor parameters") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    for (JavaConstructor constructor : javaClass.getConstructors()) {
                        for (JavaParameter parameter : constructor.getParameters()) {
                            if (parameter.isAnnotatedWith(Lazy.class)) {
                                events.add(SimpleConditionEvent.violated(
                                        parameter,
                                        constructor.getFullName() + " uses @Lazy on parameter of type "
                                                + parameter.getRawType().getName()
                                                + "; lazy CGLIB proxies are unsupported in GraalVM native images"));
                            }
                        }
                    }
                }
            })
            .because("GraalVM native images cannot build the CGLIB lazy-resolution proxy that @Lazy triggers;"
                    + " break dependency cycles with ObjectProvider or restructure instead");

    @ArchTest
    public static final ArchRule NO_RAW_VIRTUAL_THREAD_START = noClasses()
            .should()
            .callMethod(Thread.class, "startVirtualThread", Runnable.class)
            .because("Raw Thread.startVirtualThread is untracked; route work through a named executor"
                    + " bean so tests can drain background work before truncating the schema");

    @ArchTest
    public static final ArchRule EXECUTOR_CREATION_ONLY_IN_ASYNC_CONFIG = classes()
            .that()
            .doNotHaveFullyQualifiedName(AsyncConfig.class.getName())
            .should(new ArchCondition<JavaClass>("not create executors or schedulers directly") {
                // Scoped try-with-resources executors, and WebSocket cleanup schedulers that only
                // touch in-memory session registries.
                private static final Set<String> ALLOWED_ORIGINS = Set.of(
                        VirtualKeyService.class.getName() + "#fetchUsage",
                        DimensionResearchService.class.getName() + "#researchDimensions",
                        ClientWebSocketHandler.class.getName() + "#<init>",
                        EnvironmentWebSocketHandler.class.getName() + "#<init>");

                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    for (JavaConstructorCall call : javaClass.getConstructorCallsFromSelf()) {
                        if (isExecutorOrScheduler(call.getTargetOwner()) && notAllowedOrigin(call.getOrigin())) {
                            events.add(SimpleConditionEvent.violated(
                                    call,
                                    describe(call.getOrigin(), call.getTarget().getFullName())));
                        }
                    }
                    for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                        if (isExecutorFactory(call) && notAllowedOrigin(call.getOrigin())) {
                            events.add(SimpleConditionEvent.violated(
                                    call,
                                    describe(call.getOrigin(), call.getTarget().getFullName())));
                        }
                    }
                }

                private boolean isExecutorOrScheduler(JavaClass owner) {
                    return owner.isAssignableTo(ExecutorService.class)
                            || owner.isAssignableTo(ScheduledExecutorService.class)
                            || owner.isAssignableTo(TaskExecutor.class)
                            || owner.isAssignableTo(TaskScheduler.class);
                }

                private boolean isExecutorFactory(JavaMethodCall call) {
                    JavaClass owner = call.getTargetOwner();
                    if (owner.isEquivalentTo(Executors.class)) {
                        return true;
                    }
                    return owner.isEquivalentTo(Thread.class)
                            && (call.getTarget().getName().equals("ofVirtual")
                                    || call.getTarget().getName().equals("startVirtualThread"));
                }

                private boolean notAllowedOrigin(JavaCodeUnit origin) {
                    return !ALLOWED_ORIGINS.contains(origin.getOwner().getName() + "#" + origin.getName());
                }

                private String describe(JavaCodeUnit origin, String targetFullName) {
                    return origin.getFullName() + " creates " + targetFullName
                            + " outside AsyncConfig; add it to AsyncConfig and"
                            + " DrainExecutorsTestExecutionListener";
                }
            })
            .because("Executors and schedulers must be created in AsyncConfig so tests can drain them;"
                    + " untracked background DB work races the schema truncation");

    @ArchTest
    public static final ArchRule NO_CIRCULAR_DEPENDENCIES = SlicesRuleDefinition.slices()
            .matching("com.kratisai.controlplane.(*)..")
            .should()
            .beFreeOfCycles()
            .ignoreDependency(
                    DescribedPredicate.alwaysTrue(), new DescribedPredicate<JavaClass>("api dto and config packages") {
                        @Override
                        public boolean test(JavaClass javaClass) {
                            String pkg = javaClass.getPackageName();
                            return pkg.startsWith("com.kratisai.controlplane.api.restdto")
                                    || pkg.startsWith("com.kratisai.controlplane.api.wsdto")
                                    || pkg.startsWith("com.kratisai.controlplane.api.wsprotocol")
                                    || pkg.startsWith("com.kratisai.controlplane.config");
                        }
                    })
            .ignoreDependency(
                    new DescribedPredicate<JavaClass>("config package source") {
                        @Override
                        public boolean test(JavaClass javaClass) {
                            return javaClass.getPackageName().startsWith("com.kratisai.controlplane.config");
                        }
                    },
                    DescribedPredicate.alwaysTrue());

    /**
     * Client fan-out must use AFTER_COMMIT without fallbackExecution. Events published outside a
     * transaction are dropped by design — fix the publisher to be {@code @Transactional}.
     */
    @ArchTest
    public static final ArchRule NO_FALLBACK_EXECUTION_ON_TRANSACTIONAL_LISTENERS = methods()
            .that()
            .areAnnotatedWith(TransactionalEventListener.class)
            .should(new ArchCondition<JavaMethod>("not set fallbackExecution = true") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    TransactionalEventListener annotation =
                            method.getAnnotationOfType(TransactionalEventListener.class);
                    if (annotation.fallbackExecution()) {
                        events.add(SimpleConditionEvent.violated(
                                method,
                                method.getFullName()
                                        + " sets fallbackExecution=true; publish domain events inside"
                                        + " @Transactional methods instead"));
                    }
                }
            })
            .because("fallbackExecution papers over missing transactions and reintroduces stale-read races;"
                    + " always publish client fan-out events inside @Transactional methods");

    @ArchTest
    public static final ArchRule ONLY_DISPATCH_CALLS_WEBSOCKET_SEND = noClasses()
            .that()
            .doNotHaveFullyQualifiedName(WebSocketDispatch.class.getName())
            .should()
            .callMethod(WebSocketSession.class, "sendMessage", WebSocketMessage.class)
            .because("All WebSocket writes must flow through WebSocketDispatch so serialization"
                    + " and the send-or-fail-loudly error policy live in exactly one place");

    @ArchTest
    public static final ArchRule HANDLERS_NEVER_TOUCH_WEBSOCKET_SESSION = noClasses()
            .that()
            .areAssignableTo(ClientRpcHandler.class)
            .or()
            .areAssignableTo(EnvironmentRpcHandler.class)
            .should()
            .dependOnClassesThat()
            .areAssignableTo(WebSocketSession.class)
            .because("RPC handlers receive a session id and return a Flux; only transport"
                    + " infrastructure may use the raw session");

    @ArchTest
    public static final ArchRule HANDLERS_NEVER_TOUCH_WEBSOCKET_DISPATCH = noClasses()
            .that()
            .areAssignableTo(ClientRpcHandler.class)
            .or()
            .areAssignableTo(EnvironmentRpcHandler.class)
            .should()
            .dependOnClassesThat()
            .areAssignableTo(WebSocketDispatch.class)
            .because("RPC handlers return a Flux of typed payloads; the transport subscribes"
                    + " and writes through WebSocketDispatch");

    /**
     * Business code must not fan-out to client WebSockets directly. Publish domain events; only
     * {@link ClientRealtimeEventListeners} may call {@code WebSocketDispatch.broadcastNotification*}.
     */
    @ArchTest
    public static final ArchRule ONLY_REALTIME_LISTENERS_BROADCAST_TO_CLIENTS = noClasses()
            .that()
            .doNotHaveFullyQualifiedName(ClientRealtimeEventListeners.class.getName())
            .and()
            .doNotHaveFullyQualifiedName(WebSocketDispatch.class.getName())
            .should()
            .callMethodWhere(new DescribedPredicate<JavaMethodCall>("call WebSocketDispatch.broadcastNotification*") {
                @Override
                public boolean test(JavaMethodCall call) {
                    return call.getTarget().getOwner().isEquivalentTo(WebSocketDispatch.class)
                            && call.getTarget().getName().startsWith("broadcastNotification");
                }
            })
            .because("Client WebSocket fan-out must go through domain events + ClientRealtimeEventListeners;"
                    + " session-directed streaming (chat/canvas) is exempt and does not use broadcast");

    @ArchTest
    public static final ArchRule ONLY_TRANSPORT_DEPENDS_ON_WEBSOCKET_DISPATCH = noClasses()
            .that()
            .doNotHaveFullyQualifiedName(WebSocketDispatch.class.getName())
            .and()
            .doNotHaveFullyQualifiedName(ClientWebSocketHandler.class.getName())
            .and()
            .doNotHaveFullyQualifiedName(EnvironmentWebSocketHandler.class.getName())
            .and()
            .doNotHaveFullyQualifiedName(EnvironmentRpcClient.class.getName())
            .and()
            .doNotHaveFullyQualifiedName(ClientRealtimeEventListeners.class.getName())
            .should()
            .dependOnClassesThat()
            .areAssignableTo(WebSocketDispatch.class)
            .because("Only inbound routers, EnvironmentRpcClient, and ClientRealtimeEventListeners"
                    + " may use WebSocketDispatch");

    /**
     * Methods that call {@code ApplicationEventPublisher.publishEvent} that AFTER_COMMIT listeners
     * consume must run inside a transaction — either declaratively ({@code @Transactional} /
     * {@code @TransactionalEventListener}) or by being a private helper (private methods can't carry
     * their own {@code @Transactional} under Spring AOP; they are only reachable from a transactional
     * caller, verified by code review, not proxying).
     *
     * <p>A small, explicit allow-list covers methods that publish inside a programmatic {@code
     * TransactionTemplate} block, which ArchUnit cannot statically verify. Do not grow this list
     * without moving the publish call to a declaratively transactional method instead.
     */
    @ArchTest
    public static final ArchRule PUBLISH_EVENT_REQUIRES_TRANSACTION_BOUNDARY = methods()
            .that(callsPublishEvent())
            .and()
            .arePublic()
            .should(
                    new ArchCondition<JavaMethod>("be @Transactional, @TransactionalEventListener,"
                            + " or an explicitly allow-listed publisher") {
                        // Methods that publish inside a programmatic TransactionTemplate block — ArchUnit
                        // cannot statically verify this, so it is asserted here by hand. Do not grow this list
                        // without moving the publish call to a declaratively @Transactional method instead.
                        private static final Set<String> TRANSACTION_TEMPLATE_PUBLISHERS = Set.of(
                                "com.kratisai.controlplane.service.SandboxExecutionService.createExecution(java.util.UUID,"
                                        + " java.util.UUID,"
                                        + " com.kratisai.controlplane.api.restdto.CreateSandboxExecutionRequest)",
                                "com.kratisai.controlplane.service.SandboxExecutionService.cancelPendingPermissions("
                                        + "com.kratisai.controlplane.model.SandboxExecution,"
                                        + " java.lang.String)",
                                "com.kratisai.controlplane.ingestion.IngestionWorker.runIngestion(java.util.UUID)",
                                "com.kratisai.controlplane.ingestion.IngestionWorker.markBatchStarted(java.util.UUID)",
                                "com.kratisai.controlplane.ingestion.IngestionWorker.markBatchFailed(java.util.UUID,"
                                        + " java.lang.Throwable,"
                                        + " com.kratisai.controlplane.ingestion.IngestionBatchLogService$BatchLogger)");

                        // Methods that publish an event with no @TransactionalEventListener consumer at all —
                        // e.g. EnvironmentDisconnectedEvent is only ever consumed by a plain @EventListener
                        // that manages its own transaction, so the publisher has no AFTER_COMMIT ordering
                        // requirement. If a @TransactionalEventListener is ever added for this event type,
                        // remove the publisher from this list and make it @Transactional instead.
                        private static final Set<String> NON_AFTER_COMMIT_EVENT_PUBLISHERS = Set.of(
                                "com.kratisai.controlplane.service.EnvironmentSessionRegistry.removeSession(java.lang.String)",
                                "com.kratisai.controlplane.service.EnvironmentSessionRegistry.cleanupStaleSessions()");

                        @Override
                        public void check(JavaMethod method, ConditionEvents events) {
                            boolean isTransactional = method.isAnnotatedWith(Transactional.class)
                                    || method.isAnnotatedWith(TransactionalEventListener.class);
                            boolean isAllowListed = TRANSACTION_TEMPLATE_PUBLISHERS.contains(method.getFullName())
                                    || NON_AFTER_COMMIT_EVENT_PUBLISHERS.contains(method.getFullName());

                            if (!isTransactional && !isAllowListed) {
                                events.add(SimpleConditionEvent.violated(
                                        method,
                                        method.getFullName() + " calls publishEvent but is not @Transactional or"
                                                + " @TransactionalEventListener; AFTER_COMMIT listeners will silently"
                                                + " drop the event. If publishing inside a TransactionTemplate block, or"
                                                + " the event has no AFTER_COMMIT consumer, add the method to the"
                                                + " relevant allow-list in this rule."));
                            }
                        }
                    })
            .because("Events consumed by AFTER_COMMIT listeners must always be published inside a"
                    + " transaction boundary — this is the single pattern that makes fallbackExecution"
                    + " unnecessary and eliminates stale-read races by construction.");

    @ArchTest
    public static final ArchRule NO_SLEEP_INSIDE_TRANSACTIONS = methods()
            .that()
            .areAnnotatedWith(Transactional.class)
            .or()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(Transactional.class)
            .should(new ArchCondition<JavaMethod>("not call Thread.sleep()") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
                        if (call.getTarget().getOwner().isEquivalentTo(Thread.class)
                                && call.getTarget().getName().equals("sleep")) {
                            String message =
                                    String.format("Transactional method %s calls Thread.sleep()", method.getFullName());
                            events.add(SimpleConditionEvent.violated(method, message));
                        }
                    }
                }
            })
            .because("Database transactions must never hold connection locks across Thread.sleep();"
                    + " perform delays outside the transaction boundary.");

    @ArchTest
    public static final ArchRule TOOLS_USE_KRATIS_TOOL_ANNOTATION = noMethods()
            .should()
            .beAnnotatedWith(Tool.class)
            .because("raw @Tool uses DefaultToolCallResultConverter, which passes a valid-JSON String"
                    + " result through verbatim; schema $ref/$defs keys then reach Gemini as"
                    + " function_response references and fail the request. Use @KratisTool instead.");

    @ArchTest
    public static final ArchRule KRATIS_TOOLS_RETURN_SAFE_TYPES = methods()
            .that()
            .areAnnotatedWith(KratisTool.class)
            .should(new ArchCondition<JavaMethod>("return String, a record, or a List of records") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    if (!isAllowedReturnType(method.getReturnType())) {
                        events.add(SimpleConditionEvent.violated(
                                method,
                                method.getFullName() + " returns "
                                        + method.getReturnType().getName()
                                        + "; @KratisTool methods must return String, a record, or a List"
                                        + " of records so JSON-Schema reference keys cannot reach the model"));
                    }
                }

                private boolean isAllowedReturnType(JavaType returnType) {
                    JavaClass raw = returnType.toErasure();
                    if (raw.isEquivalentTo(String.class) || raw.isRecord()) {
                        return true;
                    }
                    if (raw.isEquivalentTo(List.class) && returnType instanceof JavaParameterizedType parameterized) {
                        List<JavaType> arguments = parameterized.getActualTypeArguments();
                        return arguments.size() == 1
                                && arguments.get(0).toErasure().isRecord();
                    }
                    return false;
                }
            })
            .because("Kratis tool results are serialized into function_response.response; arbitrary-key"
                    + " containers (Map, JsonNode, Object) can reintroduce $ref/$defs keys that Gemini"
                    + " resolves as part references");

    private static DescribedPredicate<JavaMethod> callsPublishEvent() {
        return new DescribedPredicate<JavaMethod>("call ApplicationEventPublisher.publishEvent") {
            @Override
            public boolean test(JavaMethod method) {
                return method.getMethodCallsFromSelf().stream()
                        .anyMatch(call -> call.getTarget().getOwner().isAssignableTo(ApplicationEventPublisher.class)
                                && call.getTarget().getName().equals("publishEvent"));
            }
        };
    }
}
