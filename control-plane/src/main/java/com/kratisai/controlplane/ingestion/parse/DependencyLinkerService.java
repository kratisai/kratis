package com.kratisai.controlplane.ingestion.parse;

import com.kratisai.controlplane.model.CtxEdge;
import com.kratisai.controlplane.model.CtxNode;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.NodeType;
import com.kratisai.controlplane.model.RelationType;
import com.kratisai.controlplane.repository.CtxEdgeRepository;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nodes are identified/persisted by AstParserService - here we create edges from one repo to
 * another.
 */
@Service
public class DependencyLinkerService {

    private static final Logger logger = LoggerFactory.getLogger(DependencyLinkerService.class);

    private final CtxNodeRepository ctxNodeRepository;
    private final CtxEdgeRepository ctxEdgeRepository;

    public DependencyLinkerService(CtxNodeRepository ctxNodeRepository, CtxEdgeRepository ctxEdgeRepository) {
        this.ctxNodeRepository = ctxNodeRepository;
        this.ctxEdgeRepository = ctxEdgeRepository;
    }

    @Transactional
    public void prepareLinks(IngestionBatch newlyIngestedBatch) {
        logger.info("Starting dependency linking for batch {}", newlyIngestedBatch.getId());

        // Find all nodes in the newly ingested batch
        // Since we are inside the transaction, we can access the nodes list cleanly or
        // query via DB
        List<CtxNode> newlyIngestedNodes = newlyIngestedBatch.getNodes();
        if (newlyIngestedNodes == null || newlyIngestedNodes.isEmpty()) {
            logger.info("No nodes found in batch {} for dependency linking", newlyIngestedBatch.getId());
            return;
        }

        List<CtxEdge> crossRepoEdges = new ArrayList<>();

        for (CtxNode node : newlyIngestedNodes) {
            // Target CLIENT nodes representing integration paths
            if (node.getNodeType() == NodeType.CLIENT) {
                String path = node.getSymbolName() != null ? node.getSymbolName() : node.getPath();
                if (path == null || path.isBlank()) {
                    continue;
                }

                // Query for matching paths in other active repository batches under the same
                // Team
                List<CtxNode> targetNodes = ctxNodeRepository.findTargetCrossRepoNodes(
                        newlyIngestedBatch.getRepository().getTeam().getId(),
                        newlyIngestedBatch.getRepository().getName(),
                        path);

                for (CtxNode targetNode : targetNodes) {
                    logger.info(
                            "Linking unresolved path '{}' in repo '{}' to concrete route in repo '{}'",
                            path,
                            newlyIngestedBatch.getRepository().getName(),
                            targetNode.getRepoName());

                    CtxEdge crossEdge = new CtxEdge(
                            newlyIngestedBatch,
                            newlyIngestedBatch.getRepository().getTeam().getId(),
                            node,
                            targetNode,
                            RelationType.CROSS_REPO_CALL);
                    crossRepoEdges.add(crossEdge);
                }
            }
        }

        if (!crossRepoEdges.isEmpty()) {
            ctxEdgeRepository.saveAll(crossRepoEdges);
            logger.info(
                    "Successfully resolved and saved {} dependency edges for batch {}",
                    crossRepoEdges.size(),
                    newlyIngestedBatch.getId());
        } else {
            logger.info("No cross-repository interactions matched for batch {}", newlyIngestedBatch.getId());
        }
    }
}
