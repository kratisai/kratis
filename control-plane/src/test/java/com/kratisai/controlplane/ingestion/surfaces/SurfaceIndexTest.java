package com.kratisai.controlplane.ingestion.surfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.kratisai.controlplane.model.CtxNode;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.NodeType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SurfaceIndexTest {

    @Test
    void groupsFilesByDirectoryAndRetainsBoundaryFactsWhileExcludingParserMarkedTests() {
        UUID teamId = UUID.randomUUID();
        IngestionBatch batch = mock(IngestionBatch.class);
        CtxNode yamlFile = node(batch, teamId, NodeType.FILE, "src/app.yaml");
        yamlFile.setMetadata("{\"language\":\"yaml\"}");
        CtxNode env = node(batch, teamId, NodeType.VARIABLE, "src/app.yaml");
        env.setSymbolName("POSTGRES_DB");
        env.setMetadata("{\"cmmLabel\":\"EnvVar\",\"env_key\":\"DB_HOST\"}");
        CtxNode tsFile = node(batch, teamId, NodeType.FILE, "src/main.ts");
        tsFile.setMetadata("{\"language\":\"typescript\"}");
        CtxNode route = node(batch, teamId, NodeType.ROUTE, "src/main.ts");
        route.setSymbolName("/orders");
        CtxNode function = node(batch, teamId, NodeType.FUNCTION, "src/main.ts");
        function.setSymbolName("connect");
        function.setMetadata(
                "{\"cmmLabel\":\"Function\",\"is_entry_point\":true,\"return_type\":\"Response\",\"param_names\":[\"request\"]}");
        CtxNode channel = node(batch, teamId, NodeType.CHANNEL, "src/main.ts");
        channel.setSymbolName("game.events");
        channel.setMetadata("{\"transport\":\"socketio\"}");
        CtxNode terraformFile = node(batch, teamId, NodeType.FILE, "terraform/main.tf");
        terraformFile.setMetadata("{\"language\":\"hcl\"}");
        CtxNode resource = node(batch, teamId, NodeType.CLASS, "terraform/main.tf");
        resource.setSymbolName("resource.aws_s3_bucket.artifacts");
        CtxNode testFile = node(batch, teamId, NodeType.MODULE, "src/app.test.ts");
        testFile.setMetadata("{\"is_test\":true,\"language\":\"typescript\"}");

        String index = SurfaceIndex.build(
                List.of(yamlFile, env, tsFile, route, function, channel, terraformFile, resource, testFile),
                List.of("src/app.yaml", "src/main.ts", "terraform/main.tf", "src/app.test.ts"),
                Set.of(),
                SurfaceIndex.DEFAULT_MAX_CHARS);

        assertThat(index)
                .contains("src/  2 files | yaml=1 typescript=1", "resources=0 routes=1 channels=1")
                .contains("src/app.yaml [yaml] env=POSTGRES_DB")
                .contains(
                        "src/main.ts [typescript] route=/orders, entry=Function:connect returns=Response params=request, channel=game.events (socketio)")
                .contains("terraform/  1 files | hcl=1", "symbol=Class:resource.aws_s3_bucket.artifacts")
                .doesNotContain("src/app.test.ts");
    }

    @Test
    void summarizesRepositoryLanguagesCollapsesLargeDirectoriesAndKeepsBoundaryFilesAndSmallDirsInline() {
        UUID teamId = UUID.randomUUID();
        IngestionBatch batch = mock(IngestionBatch.class);
        List<CtxNode> nodes = new ArrayList<>();
        List<String> inventory = new ArrayList<>();

        for (int i = 0; i < 16; i++) {
            String path = "src/ui/Button" + String.format("%02d", i) + ".tsx";
            inventory.add(path);
            CtxNode file = node(batch, teamId, NodeType.FILE, path);
            file.setMetadata("{\"language\":\"tsx\"}");
            nodes.add(file);
        }

        String payloadPath = "src/ui/SupplyChainPayloadTypes.ts";
        inventory.add(payloadPath);
        CtxNode payloadFile = node(batch, teamId, NodeType.FILE, payloadPath);
        payloadFile.setMetadata("{\"language\":\"typescript\"}");
        nodes.add(payloadFile);
        for (int i = 0; i < 8; i++) {
            CtxNode payloadIface = node(batch, teamId, NodeType.INTERFACE, payloadPath);
            payloadIface.setSymbolName("Event" + i);
            payloadIface.setMetadata("{\"base_classes\":[\"PlayerAction\"]}");
            nodes.add(payloadIface);
        }

        inventory.add("terraform/s3-portal.tf");
        inventory.add("terraform/s3-supplychain.tf");

        String index = SurfaceIndex.build(nodes, inventory, Set.of(), SurfaceIndex.DEFAULT_MAX_CHARS);

        assertThat(index)
                .contains("Repository summary: 19 files across 2 directories | languages: tsx=16 tf=2 typescript=1")
                .contains("src/ui/  17 files | tsx=16 typescript=1")
                .contains("src/ui/SupplyChainPayloadTypes.ts [typescript] symbol=Interface:Event0 extends PlayerAction")
                .contains("(+2 more)")
                .contains("(+16 other files in src/ui/; use list_files or search_files to inspect)")
                .contains("terraform/s3-portal.tf [tf]", "terraform/s3-supplychain.tf [tf]")
                .doesNotContain("Button00.tsx");
    }

    private static CtxNode node(IngestionBatch batch, UUID teamId, NodeType type, String path) {
        return new CtxNode(batch, teamId, "repo", type, path);
    }
}
