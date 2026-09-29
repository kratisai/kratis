package com.kratisai.controlplane.service;

import com.kratisai.controlplane.model.CtxNode;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GraphQueryService {

    private static final Logger logger = LoggerFactory.getLogger(GraphQueryService.class);
    private final CtxNodeRepository ctxNodeRepository;

    public GraphQueryService(CtxNodeRepository ctxNodeRepository) {
        this.ctxNodeRepository = ctxNodeRepository;
    }

    @Transactional(readOnly = true)
    public List<CtxNode> getDependencies(UUID teamId, String repoName, String path, int depth) {
        logger.debug("Fetching downstream dependencies for {} in {} (depth {})", path, repoName, depth);
        return ctxNodeRepository.findDownstreamDependencies(teamId, repoName, path, depth);
    }

    @Transactional(readOnly = true)
    public List<CtxNode> getUsages(UUID teamId, String repoName, String path, int depth) {
        logger.debug("Fetching upstream usages for {} in {} (depth {})", path, repoName, depth);
        return ctxNodeRepository.findUpstreamUsages(teamId, repoName, path, depth);
    }
}
