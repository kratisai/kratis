package com.kratisai.controlplane.service;

import com.kratisai.controlplane.api.restdto.TeamSkillResponse;
import com.kratisai.controlplane.api.restdto.UpdateTeamSkillRequest;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.TeamSkill;
import com.kratisai.controlplane.repository.TeamRepository;
import com.kratisai.controlplane.repository.TeamSkillRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TeamSkillService {

    private static final Logger logger = LoggerFactory.getLogger(TeamSkillService.class);

    private static final long MAX_ARCHIVE_BYTES = 10L * 1024 * 1024;

    private final TeamSkillRepository teamSkillRepository;
    private final TeamRepository teamRepository;
    private final TeamService teamService;
    private final BlobStorageService blobStorageService;
    private final SkillArchiveService archiveService;
    private final SkillFrontmatterParser frontmatterParser;

    public TeamSkillService(
            TeamSkillRepository teamSkillRepository,
            TeamRepository teamRepository,
            TeamService teamService,
            BlobStorageService blobStorageService,
            SkillArchiveService archiveService,
            SkillFrontmatterParser frontmatterParser) {
        this.teamSkillRepository = teamSkillRepository;
        this.teamRepository = teamRepository;
        this.teamService = teamService;
        this.blobStorageService = blobStorageService;
        this.archiveService = archiveService;
        this.frontmatterParser = frontmatterParser;
    }

    @Transactional(readOnly = true)
    public List<TeamSkillResponse> listSkills(UUID userId, UUID teamId) {
        teamService.validateMembership(userId, teamId);
        return teamSkillRepository.findByTeamId(teamId).stream()
                .sorted(Comparator.comparing(TeamSkill::getName))
                .map(TeamSkillResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public TeamSkillResponse getSkill(UUID userId, UUID teamId, UUID skillId) {
        teamService.validateMembership(userId, teamId);
        return TeamSkillResponse.from(requireSkill(teamId, skillId));
    }

    @Transactional
    public TeamSkillResponse uploadSkill(UUID userId, UUID teamId, MultipartFile file) {
        teamService.validateMembership(userId, teamId);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill archive is required");
        }
        if (file.getSize() > MAX_ARCHIVE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill archive is too large");
        }

        byte[] uploaded;
        try {
            uploaded = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to read skill archive", e);
        }
        archiveService.validate(uploaded);

        Path tempDirectory = null;
        try {
            tempDirectory = Files.createTempDirectory("kratis-skill-upload-");
            archiveService.extract(uploaded, tempDirectory);
            Path skillRoot = locateSkillRoot(tempDirectory);
            String wrapperName = skillRoot.equals(tempDirectory)
                    ? null
                    : Optional.ofNullable(skillRoot.getFileName())
                            .map(Path::toString)
                            .orElse(null);
            SkillFrontmatterParser.ParsedSkill skill =
                    frontmatterParser.parseStrict(skillRoot.resolve("SKILL.md"), wrapperName);

            if (teamSkillRepository.findByTeamIdAndName(teamId, skill.name()).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Skill already exists");
            }

            byte[] archive = archiveService.createArchive(skillRoot);
            String blobPath = blobPath(teamId, skill.name());
            blobStorageService.putObject(blobPath, archive, "application/gzip");

            Team team = teamRepository
                    .findById(teamId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
            TeamSkill entity = new TeamSkill();
            entity.setTeam(team);
            entity.setName(skill.name());
            entity.setDescription(skill.description());
            entity.setBlobPath(blobPath);
            entity.setChecksumSha256(archiveService.sha256(archive));
            entity.setEnabled(true);

            try {
                TeamSkill saved = teamSkillRepository.save(entity);
                logger.info("Uploaded skill '{}' for team {}", skill.name(), teamId);
                return TeamSkillResponse.from(saved);
            } catch (DataIntegrityViolationException e) {
                blobStorageService.deleteObject(blobPath);
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Skill already exists", e);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to stage skill archive", e);
        } finally {
            deleteRecursively(tempDirectory);
        }
    }

    @Transactional
    public TeamSkillResponse updateSkill(UUID userId, UUID teamId, UUID skillId, UpdateTeamSkillRequest request) {
        teamService.validateMembership(userId, teamId);
        TeamSkill skill = requireSkill(teamId, skillId);
        if (request.enabled() != null) {
            skill.setEnabled(request.enabled());
        }
        return TeamSkillResponse.from(teamSkillRepository.save(skill));
    }

    @Transactional
    public void deleteSkill(UUID userId, UUID teamId, UUID skillId) {
        teamService.validateMembership(userId, teamId);
        TeamSkill skill = requireSkill(teamId, skillId);
        blobStorageService.deleteObject(skill.getBlobPath());
        teamSkillRepository.delete(skill);
        logger.info("Deleted skill '{}' for team {}", skill.getName(), teamId);
    }

    private TeamSkill requireSkill(UUID teamId, UUID skillId) {
        return teamSkillRepository
                .findByTeamIdAndId(teamId, skillId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill not found"));
    }

    private static Path locateSkillRoot(Path extractionRoot) {
        Path rootSkill = extractionRoot.resolve("SKILL.md");
        if (Files.isRegularFile(rootSkill)) {
            return extractionRoot;
        }
        try (Stream<Path> children = Files.list(extractionRoot)) {
            List<Path> directories = children.filter(Files::isDirectory).toList();
            for (Path directory : directories) {
                if (Files.isRegularFile(directory.resolve("SKILL.md"))) {
                    return directory;
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to inspect skill archive", e);
        }
        throw new IllegalArgumentException("Skill archive must contain SKILL.md");
    }

    private static String blobPath(UUID teamId, String skillName) {
        return "skills/teams/" + teamId + "/" + skillName + ".tar.gz";
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    logger.warn("Unable to delete temporary skill path {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            logger.warn("Unable to clean temporary skill directory {}: {}", directory, e.getMessage());
        }
    }
}
