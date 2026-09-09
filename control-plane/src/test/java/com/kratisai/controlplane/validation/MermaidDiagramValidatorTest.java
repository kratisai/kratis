package com.kratisai.controlplane.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MermaidDiagramValidatorTest {

    private final MermaidDiagramValidator validator = new MermaidDiagramValidator();

    @Test
    void findIssues_returnsEmptyForNullOrBlankContent() {
        assertThat(validator.findIssues(null)).isEmpty();
        assertThat(validator.findIssues("   ")).isEmpty();
    }

    @Test
    void findIssues_returnsEmptyForContentWithoutMermaidBlocks() {
        assertThat(validator.findIssues("# Just markdown\n\nNo diagrams here.")).isEmpty();
    }

    @Test
    void findIssues_flagsUnquotedSubgraphTitleWithParentheses() {
        String content = """
                ```mermaid
                graph TD
                    subgraph Client Tier
                        UI[React SPA]
                    end
                    subgraph Control Plane Tier (Spring Boot)
                        API[REST Controllers]
                    end
                ```
                """;

        List<String> issues = validator.findIssues(content);

        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst())
                .contains("diagram #1, line 5")
                .contains("Control Plane Tier (Spring Boot)")
                .contains("subgraph \"Control Plane Tier (Spring Boot)\"");
    }

    @Test
    void findIssues_acceptsQuotedSubgraphTitleWithParentheses() {
        String content = """
                ```mermaid
                graph TD
                    subgraph "Control Plane Tier (Spring Boot)"
                        API[REST Controllers]
                    end
                ```
                """;

        assertThat(validator.findIssues(content)).isEmpty();
    }

    @Test
    void findIssues_acceptsSingleQuotedSubgraphTitleWithParentheses() {
        String content = """
                ```mermaid
                graph TD
                    subgraph 'Control Plane Tier (Spring Boot)'
                        API[REST Controllers]
                    end
                ```
                """;

        assertThat(validator.findIssues(content)).isEmpty();
    }

    @Test
    void findIssues_flagsBracketedSubgraphTitleWithParentheses() {
        String content = """
                ```mermaid
                graph TD
                    subgraph cpt[Control Plane Tier (Spring Boot)]
                        API[REST Controllers]
                    end
                ```
                """;

        List<String> issues = validator.findIssues(content);

        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst())
                .contains("diagram #1, line 2")
                .contains("subgraph title")
                .contains("Control Plane Tier (Spring Boot)");
    }

    @Test
    void findIssues_acceptsBracketedSubgraphTitleWithoutSpecialChars() {
        String content = """
                ```mermaid
                graph TD
                    subgraph cpt[Control Plane Tier]
                        API[REST Controllers]
                    end
                ```
                """;

        assertThat(validator.findIssues(content)).isEmpty();
    }

    @Test
    void findIssues_flagsNodeLabelWithUnquotedParentheses() {
        String content = """
                ```mermaid
                graph LR
                    GIT[Git Providers (GitHub/GitLab/ADO)]
                ```
                """;

        List<String> issues = validator.findIssues(content);

        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst())
                .contains("diagram #1, line 2")
                .contains("node \"GIT\"")
                .contains("Git Providers (GitHub/GitLab/ADO)");
    }

    @Test
    void findIssues_acceptsQuotedNodeLabelWithParentheses() {
        String content = """
                ```mermaid
                graph LR
                    GIT["Git Providers (GitHub/GitLab/ADO)"]
                ```
                """;

        assertThat(validator.findIssues(content)).isEmpty();
    }

    @Test
    void findIssues_flagsNodeLabelWithOtherShapeDelimiters() {
        String content = """
                ```mermaid
                graph TD
                    A[text with | pipe]
                    B{rhombus (x)}
                    C(round (nested))
                ```
                """;

        List<String> issues = validator.findIssues(content);

        assertThat(issues).hasSize(3);
        assertThat(issues.get(0)).contains("line 2").contains("node \"A\"");
        assertThat(issues.get(1)).contains("line 3").contains("node \"B\"");
        assertThat(issues.get(2)).contains("line 4").contains("node \"C\"");
    }

    @Test
    void findIssues_acceptsNodeLabelsWithSafeCharacters() {
        String content = """
                ```mermaid
                graph LR
                    DEV[Developer / User]
                    UI[Web: Frontend]
                    SB[Sandbox #1; primary]
                    CP[Control, Plane]
                ```
                """;

        assertThat(validator.findIssues(content)).isEmpty();
    }

    @Test
    void findIssues_flagsFullDiagramWithUnsafeNodeLabels() {
        String content = """
                ```mermaid
                graph LR
                    subgraph "External Actors"
                        DEV[Developer / User]
                        ADMIN[Enterprise Admin]
                    end

                    subgraph "Kratis Platform"
                        CP[Control Plane Service]
                        UI[Web Frontend]
                        SB[Execution Sandboxes]
                    end

                    subgraph "External Services"
                        GIT[Git Providers (GitHub/GitLab/ADO)]
                        LLM[LLM Gateways (LiteLLM / OpenAI)]
                    end

                    DEV --> UI
                    ADMIN --> UI
                    UI --> CP
                    CP --> GIT
                    CP --> LLM
                    CP --> SB
                ```
                """;

        List<String> issues = validator.findIssues(content);

        assertThat(issues).hasSize(2);
        assertThat(issues.get(0)).contains("diagram #1, line 14").contains("node \"GIT\"");
        assertThat(issues.get(1)).contains("diagram #1, line 15").contains("node \"LLM\"");
    }

    @Test
    void findIssues_doesNotFlagEdgeLinkSyntax() {
        String content = """
                ```mermaid
                graph LR
                    A --> B
                    C -->|text| D
                    E -- edge text --> F
                ```
                """;

        assertThat(validator.findIssues(content)).isEmpty();
    }

    @Test
    void findIssues_flagsUnquotedSubgraphTitleWithColon() {
        String content = """
                ```mermaid
                graph TD
                    subgraph Control Plane: API
                        A
                    end
                ```
                """;

        List<String> issues = validator.findIssues(content);
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst()).contains("Control Plane: API");
    }

    @Test
    void findIssues_flagsEachUnsafeDiagramInOrder() {
        String content = """
                ```mermaid
                graph TD
                    subgraph Tier (One)
                        A
                    end
                ```

                ```mermaid
                graph TD
                    subgraph Tier (Two)
                        B
                    end
                ```
                """;

        List<String> issues = validator.findIssues(content);

        assertThat(issues).hasSize(2);
        assertThat(issues.get(0)).contains("diagram #1, line 2");
        assertThat(issues.get(1)).contains("diagram #2, line 2");
    }

    @Test
    void findIssues_ignoresNonMermaidFencedBlocks() {
        String content = """
                ```mermaid
                graph TD
                    subgraph Tier (One)
                        A
                    end
                ```

                ```java
                // subgraph Tier (Two) - not a mermaid block
                ```

                ```text
                subgraph Not A Diagram (Three)
                ```
                """;

        List<String> issues = validator.findIssues(content);

        // Only the real mermaid block is validated; java and text fences are ignored.
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst()).contains("diagram #1");
    }

    @Test
    void findIssues_acceptsSimpleSubgraphWithPlainTitle() {
        String content = """
                ```mermaid
                graph TD
                    subgraph Client Tier
                        UI[React SPA]
                    end
                ```
                """;

        assertThat(validator.findIssues(content)).isEmpty();
    }

    @Test
    void findIssues_flagsMissingSubgraphEnd() {
        String content = """
                ```mermaid
                graph TD
                    subgraph Client Tier
                        UI[React SPA]
                ```
                """;

        List<String> issues = validator.findIssues(content);

        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst()).contains("diagram #1").contains("1 subgraph(s) but only 0 'end' keyword(s)");
    }

    @Test
    void findIssues_flagsNestedSubgraphMissingEnd() {
        String content = """
                ```mermaid
                flowchart LR
                    subgraph Outer
                        subgraph Inner
                            A
                        end
                ```
                """;

        List<String> issues = validator.findIssues(content);

        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst()).contains("2 subgraph(s) but only 1 'end' keyword(s)");
    }

    @Test
    void findIssues_acceptsFlowchartWithoutSubgraphs() {
        String content = """
                ```mermaid
                graph TD
                    A --> B
                    B --> C
                ```
                """;

        assertThat(validator.findIssues(content)).isEmpty();
    }

    @Test
    void findIssues_flagsMissingSequenceBlockEnd() {
        String content = """
                ```mermaid
                sequenceDiagram
                    participant Client
                    participant Server
                    loop Retry
                        Client->>Server: request
                        Server-->>Client: response
                ```
                """;

        List<String> issues = validator.findIssues(content);

        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst())
                .contains("diagram #1")
                .contains("1 sequence block(s) (loop/alt/opt/par/rect/critical/break) but only 0 'end' keyword(s)");
    }

    @Test
    void findIssues_acceptsSequenceDiagramWithAllBlocksClosed() {
        String content = """
                ```mermaid
                sequenceDiagram
                    participant Client
                    participant Server
                    loop Retry
                        Client->>Server: request
                    end
                    alt success
                        Server-->>Client: ok
                    else failure
                        Server-->>Client: error
                    end
                ```
                """;

        assertThat(validator.findIssues(content)).isEmpty();
    }

    @Test
    void findIssues_flagsUnbalancedClassDiagramBraces() {
        String content = """
                ```mermaid
                classDiagram
                    class Animal {
                        +String name
                        +int age
                ```
                """;

        List<String> issues = validator.findIssues(content);

        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst()).contains("unbalanced braces");
    }

    @Test
    void findIssues_acceptsBalancedClassDiagramBraces() {
        String content = """
                ```mermaid
                classDiagram
                    class Animal {
                        +String name
                        +int age
                    }
                    class Dog {
                        +String breed
                    }
                    Animal <|-- Dog
                ```
                """;

        assertThat(validator.findIssues(content)).isEmpty();
    }

    @Test
    void findIssues_acceptsBalancedStateDiagramBraces() {
        String content = """
                ```mermaid
                stateDiagram-v2
                    [*] --> Still
                    state Still {
                        [*] --> one
                        one --> two
                    }
                ```
                """;

        assertThat(validator.findIssues(content)).isEmpty();
    }

    @Test
    void findIssues_doesNotFlagErDiagramCardinalityBraces() {
        String content = """
                ```mermaid
                erDiagram
                    CUSTOMER ||--o{ ORDER : places
                    ORDER ||--|{ LINE-ITEM : contains
                ```
                """;

        // Cardinality symbols (o{, |{) contain '{' with no matching '}' and are valid syntax,
        // so brace-balancing must not be applied to erDiagram.
        assertThat(validator.findIssues(content)).isEmpty();
    }

    @Test
    void findIssues_flagsInvalidDiagramType() {
        String content = """
                ```mermaid
                floachart TD
                    A --> B
                ```
                """;

        List<String> issues = validator.findIssues(content);

        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst()).contains("diagram #1").contains("no valid diagram type");
    }

    @Test
    void findIssues_acceptsInitDirectiveBeforeDiagramType() {
        String content = """
                ```mermaid
                %%{init: {"theme": "dark"}}%%
                flowchart TD
                    A --> B
                ```
                """;

        assertThat(validator.findIssues(content)).isEmpty();
    }

    @Test
    void findIssues_acceptsDiagramTypeWithSemicolon() {
        String content = """
                ```mermaid
                graph TD;
                    A --> B;
                ```
                """;

        assertThat(validator.findIssues(content)).isEmpty();
    }

    @Test
    void findIssues_ignoresSubgraphKeywordsInsideComments() {
        String content = """
                ```mermaid
                graph TD
                    %% subgraph Legacy (Deprecated)
                    A --> B
                ```
                """;

        // The commented subgraph must not count toward the subgraph/end balance.
        assertThat(validator.findIssues(content)).isEmpty();
    }

    @Test
    void findIssues_validatesMultipleDiagramsIndependently() {
        String content = """
                # Architecture

                ```mermaid
                graph TD
                    subgraph Tier (One)
                        A
                    end
                ```

                Text between diagrams.

                ```mermaid
                sequenceDiagram
                    loop Retry
                        A->>B: ping
                ```
                """;

        List<String> issues = validator.findIssues(content);

        // Diagram #1: unquoted subgraph title. Diagram #2: missing 'end' for the loop block.
        // Rules must be attributed to the correct diagram index.
        assertThat(issues).hasSize(2);
        assertThat(issues.get(0)).contains("diagram #1").contains("subgraph title");
        assertThat(issues.get(1)).contains("diagram #2").contains("sequence block");
    }

    @Test
    void findIssues_doesNotLeakEndBalanceAcrossBlocks() {
        String content = """
                ```mermaid
                graph TD
                    subgraph First (One)
                        A
                    end
                ```

                ```mermaid
                graph TD
                    subgraph Second (Two)
                ```
                """;

        List<String> issues = validator.findIssues(content);

        // Diagram #1 has a balanced subgraph/end and one unquoted title issue.
        // Diagram #2 is missing its 'end'. The 'end' from diagram #1 must not satisfy
        // diagram #2's balance.
        assertThat(issues).hasSize(3);
        assertThat(issues.get(0)).contains("diagram #1").contains("subgraph title");
        assertThat(issues.get(1)).contains("diagram #2").contains("subgraph title");
        assertThat(issues.get(2)).contains("diagram #2").contains("1 subgraph(s) but only 0 'end' keyword(s)");
    }

    @Test
    void findIssues_reportsLineNumbersRelativeToEachBlock() {
        String content = """
                ```mermaid
                graph TD
                    subgraph Tier (One)
                        A
                    end
                ```

                ```mermaid
                flowchart LR
                    subgraph Tier (Two)
                        B
                    end
                ```
                """;

        List<String> issues = validator.findIssues(content);

        assertThat(issues).hasSize(2);
        assertThat(issues.get(0)).contains("diagram #1, line 2");
        assertThat(issues.get(1)).contains("diagram #2, line 2");
    }

    @Test
    void findIssues_flagsUnquotedEdgeLabelWithParentheses() {
        String content = """
                ```mermaid
                flowchart TD
                    A --> B
                    SandboxProv -->|Generate Virtual Key (TTL + Budget)| LiteLLMSvc
                ```
                """;

        List<String> issues = validator.findIssues(content);

        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst())
                .contains("diagram #1, line 3")
                .contains("Generate Virtual Key (TTL + Budget)")
                .contains("-->|\"Generate Virtual Key (TTL + Budget)\"|");
    }

    @Test
    void findIssues_acceptsQuotedEdgeLabelWithParentheses() {
        String content = """
                ```mermaid
                flowchart TD
                    A --> B
                    SandboxProv -->|"Generate Virtual Key (TTL + Budget)"| LiteLLMSvc
                ```
                """;

        assertThat(validator.findIssues(content)).isEmpty();
    }

    @Test
    void findIssues_acceptsSingleQuotedEdgeLabelWithParentheses() {
        String content = """
                ```mermaid
                flowchart TD
                    A --> B
                    SandboxProv -->|'Generate Virtual Key (TTL + Budget)'| LiteLLMSvc
                ```
                """;

        assertThat(validator.findIssues(content)).isEmpty();
    }

    @Test
    void findIssues_flagsUnquotedEdgeLabelWithBracketsBracesAndColons() {
        String content = """
                ```mermaid
                graph LR
                    A -->|payload [0]| B
                    B -->|config {key: val}| C
                    C -->|port: 8080| D
                ```
                """;

        List<String> issues = validator.findIssues(content);

        assertThat(issues).hasSize(3);
        assertThat(issues.get(0)).contains("diagram #1, line 2").contains("payload [0]");
        assertThat(issues.get(1)).contains("diagram #1, line 3").contains("config {key: val}");
        assertThat(issues.get(2)).contains("diagram #1, line 4").contains("port: 8080");
    }

    @Test
    void findIssues_acceptsQuotedEdgeLabelWithColonsAndSlashes() {
        String content = """
                ```mermaid
                flowchart TD
                    LiteLLMSvc -->|"POST /key/generate"| LiteLLMGW
                    LiteLLMSvc -->|"port: 4000"| LiteLLMGW
                ```
                """;

        assertThat(validator.findIssues(content)).isEmpty();
    }

    @Test
    void findIssues_flagsExactWikiGenerationFailureDiagram() {
        String content = """
                ```mermaid
                flowchart TD
                    subgraph TeamConfiguration["Team Provider Configuration"]
                        Settings["Team Settings UI / REST API"]
                        ModelCreds["model_providers Table (Encrypted API Keys)"]
                    end

                    subgraph ControlPlane["Control Plane Layer"]
                        LiteLLMSvc["LiteLLMProvisioningService"]
                        SandboxProv["SandboxProvisioningService"]
                    end

                    subgraph GatewayTier["LiteLLM Proxy Tier (Port 4000)"]
                        LiteLLMGW["LiteLLM Server"]
                        SpendTracker["PostgreSQL Spend & Usage Database"]
                    end

                    subgraph SandboxedExecution["Execution Sandbox"]
                        Agent["ACP Agent Process"]
                    end

                    Settings --> LiteLLMSvc
                    LiteLLMSvc --> ModelCreds
                    LiteLLMSvc -->|Register Upstream Model| LiteLLMGW
                    SandboxProv -->|Generate Virtual Key (TTL + Budget)| LiteLLMSvc
                    LiteLLMSvc -->|POST /key/generate| LiteLLMGW
                    SandboxProv -->|Deliver Virtual Key over /ws/env| Agent
                    Agent -->|Inference via Virtual Key| LiteLLMGW
                    LiteLLMGW --> SpendTracker
                    LiteLLMGW -->|Forward with Real API Key| UpstreamLLM["Upstream LLM (OpenAI / Anthropic)"]
                ```
                """;

        List<String> issues = validator.findIssues(content);

        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst())
                .contains("diagram #1, line 24")
                .contains("Generate Virtual Key (TTL + Budget)")
                .contains("-->|\"Generate Virtual Key (TTL + Budget)\"|");
    }

    @Test
    void findIssues_acceptsExactWikiGenerationFailureDiagramWhenQuoted() {
        String content = """
                ```mermaid
                flowchart TD
                    subgraph TeamConfiguration["Team Provider Configuration"]
                        Settings["Team Settings UI / REST API"]
                        ModelCreds["model_providers Table (Encrypted API Keys)"]
                    end

                    subgraph ControlPlane["Control Plane Layer"]
                        LiteLLMSvc["LiteLLMProvisioningService"]
                        SandboxProv["SandboxProvisioningService"]
                    end

                    subgraph GatewayTier["LiteLLM Proxy Tier (Port 4000)"]
                        LiteLLMGW["LiteLLM Server"]
                        SpendTracker["PostgreSQL Spend & Usage Database"]
                    end

                    subgraph SandboxedExecution["Execution Sandbox"]
                        Agent["ACP Agent Process"]
                    end

                    Settings --> LiteLLMSvc
                    LiteLLMSvc --> ModelCreds
                    LiteLLMSvc -->|Register Upstream Model| LiteLLMGW
                    SandboxProv -->|"Generate Virtual Key (TTL + Budget)"| LiteLLMSvc
                    LiteLLMSvc -->|POST /key/generate| LiteLLMGW
                    SandboxProv -->|Deliver Virtual Key over /ws/env| Agent
                    Agent -->|Inference via Virtual Key| LiteLLMGW
                    LiteLLMGW --> SpendTracker
                    LiteLLMGW -->|Forward with Real API Key| UpstreamLLM["Upstream LLM (OpenAI / Anthropic)"]
                ```
                """;

        assertThat(validator.findIssues(content)).isEmpty();
    }

    @Test
    void findIssues_flagsMultipleUnquotedEdgeLabelsOnSameLine() {
        String content = """
                ```mermaid
                graph LR
                    A -->|step 1 (init)| B -->|step 2 [run]| C
                ```
                """;

        List<String> issues = validator.findIssues(content);

        assertThat(issues).hasSize(2);
        assertThat(issues.get(0)).contains("diagram #1, line 2").contains("step 1 (init)");
        assertThat(issues.get(1)).contains("diagram #1, line 2").contains("step 2 [run]");
    }
}
