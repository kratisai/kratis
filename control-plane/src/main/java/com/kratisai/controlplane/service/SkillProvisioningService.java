package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.wsdto.EnvironmentConnectorResult;
import com.kratisai.controlplane.api.wsdto.EnvironmentRpcPayload;
import com.kratisai.controlplane.api.wsdto.ExecStatus;
import com.kratisai.controlplane.config.SandboxProperties;
import com.kratisai.controlplane.model.SandboxExecution;
import com.kratisai.controlplane.model.Team;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SkillProvisioningService {

    private static final Logger logger = LoggerFactory.getLogger(SkillProvisioningService.class);

    private static final int BASE64_CHUNK_CHARS = 6000;

    private final SkillResolverService skillResolverService;
    private final SkillArchiveService archiveService;
    private final BlobStorageService blobStorageService;
    private final EnvironmentRpcClient environmentRpcClient;
    private final SandboxProperties sandboxProperties;

    public SkillProvisioningService(
            SkillResolverService skillResolverService,
            SkillArchiveService archiveService,
            BlobStorageService blobStorageService,
            EnvironmentRpcClient environmentRpcClient,
            SandboxProperties sandboxProperties) {
        this.skillResolverService = skillResolverService;
        this.archiveService = archiveService;
        this.blobStorageService = blobStorageService;
        this.environmentRpcClient = environmentRpcClient;
        this.sandboxProperties = sandboxProperties;
    }

    public List<SkillDefinition> provisionSkills(SandboxExecution execution)
            throws InterruptedException, TimeoutException {
        List<SkillDefinition> catalog = skillResolverService.resolveCatalog(resolveTeamId(execution));

        for (SkillDefinition skill : catalog) {
            byte[] archive = skill.source() == SkillSource.TEAM
                    ? blobStorageService.getObjectBytes(skill.path().toString())
                    : archiveService.createArchive(skill.path());
            if (archive == null || archive.length == 0) {
                logger.warn("Skipping skill '{}' because its archive is unavailable", skill.name());
                continue;
            }
            archiveService.validate(archive);
            publishSkill(execution, skill.name(), archive);
        }
        return catalog;
    }

    private void publishSkill(SandboxExecution execution, String skillName, byte[] archive)
            throws InterruptedException, TimeoutException {
        String base64 = Base64.getEncoder().encodeToString(archive);
        String target = sandboxProperties.getSkillsDir() + "/" + skillName;
        String archivePath = "/tmp/kratis-skill-" + skillName + ".tar.gz";

        List<String> commands = new ArrayList<>();
        commands.add("rm -rf '" + target + "' && mkdir -p '" + target + "' && : > '" + archivePath + "'");
        for (int i = 0; i < base64.length(); i += BASE64_CHUNK_CHARS) {
            String chunk = base64.substring(i, Math.min(i + BASE64_CHUNK_CHARS, base64.length()));
            commands.add("echo '" + chunk + "' | base64 -d >> '" + archivePath + "'");
        }
        commands.add("tar -xzf '" + archivePath + "' -C '" + target + "'");
        commands.add("chown -R 1000:1000 '" + target + "'");
        commands.add("rm -f '" + archivePath + "'");

        for (String command : commands) {
            EnvironmentConnectorResult.Exec result = environmentRpcClient.request(
                    execution.getEnvironment().getId(),
                    new EnvironmentRpcPayload.Exec(
                            command, false, execution.getId().toString()));
            if (result == null || result.status() == ExecStatus.FAILED || result.exitCode() != 0) {
                throw new IllegalStateException("Failed to provision skill '" + skillName + "': "
                        + (result != null && result.error() != null ? result.error() : "exit code"));
            }
        }
        logger.info("Provisioned skill '{}' into {}", skillName, target);
    }

    private static UUID resolveTeamId(SandboxExecution execution) {
        Team team = execution.getChat() != null ? execution.getChat().getTeam() : null;
        if (team == null && execution.getEnvironment() != null) {
            team = execution.getEnvironment().getTeam();
        }
        return team != null ? team.getId() : null;
    }
}
