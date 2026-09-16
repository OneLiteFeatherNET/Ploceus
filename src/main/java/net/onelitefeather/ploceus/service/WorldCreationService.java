package net.onelitefeather.ploceus.service;

import net.onelitefeather.ploceus.domain.Actor;
import net.onelitefeather.ploceus.domain.AuditAction;
import net.onelitefeather.ploceus.domain.OperationResult;
import net.onelitefeather.ploceus.domain.PloceusKey;
import net.onelitefeather.ploceus.domain.Project;
import net.onelitefeather.ploceus.domain.ProjectWorld;
import net.onelitefeather.ploceus.domain.WorldDefaults;
import net.onelitefeather.ploceus.persistence.WorldRepository;
import net.onelitefeather.ploceus.platform.MultiverseAdapter;
import net.onelitefeather.ploceus.platform.WorldCreationRequest;

import java.util.Objects;

import static net.onelitefeather.ploceus.domain.AuditEntry.TargetType.WORLD;

/// Creates a world through Multiverse, resolving its generator/environment defaults from the
/// owning project's preset before falling back to the config default and then vanilla.
///
/// World creation is a Bukkit operation: {@link #create} must be called on the main thread,
/// same as every other `MultiverseAdapter` call.
public class WorldCreationService {

    public record WorldCreationForm(String worldName, String displayName, String generator,
                                    String generatorSettings, String environment,
                                    boolean generateStructures, Long seed) { }

    private final MultiverseAdapter multiverse;
    private final WorldRepository worlds;
    private final AuditService audit;
    private final WorldDefaults configDefaults;

    public WorldCreationService(MultiverseAdapter multiverse, WorldRepository worlds,
                                AuditService audit, WorldDefaults configDefaults) {
        this.multiverse = multiverse;
        this.worlds = worlds;
        this.audit = audit;
        this.configDefaults = configDefaults;
    }

    /// project preset -> config default -> vanilla. Pure and static, so the resolution order
    /// is testable on its own without constructing a service.
    public static WorldDefaults resolveDefaults(Project project, WorldDefaults configDefaults) {
        WorldDefaults preset = project.getWorldDefaults();
        if (preset != null && preset.getGenerator() != null && !preset.getGenerator().isBlank()) {
            return preset;
        }
        return configDefaults;
    }

    public WorldDefaults defaultsFor(Project project) {
        return resolveDefaults(project, configDefaults);
    }

    /// Main thread only: world creation is a Bukkit operation.
    public OperationResult<ProjectWorld> create(Project project, WorldCreationForm form, Actor actor) {
        if (!PloceusKey.isValidValue(form.worldName())) {
            return OperationResult.failure("form.invalidKey", form.worldName());
        }
        PloceusKey key = PloceusKey.of(form.worldName());
        if (worlds.findByKey(key).isPresent() || worlds.findByWorldName(form.worldName()).isPresent()) {
            return OperationResult.failure("project.exists", form.worldName());
        }

        OperationResult<String> created = multiverse.createWorld(new WorldCreationRequest(
                key, form.worldName(), form.generator(), form.generatorSettings(),
                form.environment(), null, form.generateStructures(), form.seed()));
        if (created instanceof OperationResult.Failure<String> failure) {
            return OperationResult.failure(failure.messageKey(), failure.detail());
        }

        ProjectWorld world = new ProjectWorld(key, form.worldName());
        world.setDisplayName(form.displayName() == null || form.displayName().isBlank()
                ? form.worldName() : form.displayName());
        world.setProject(project);
        world.setCreatedBy(actor.uuid());
        world.setUpdatedBy(actor.uuid());
        worlds.save(world);

        multiverse.setAutoLoad(form.worldName(), false);
        multiverse.setAlias(form.worldName(), world.getDisplayName());

        audit.recordEvent(WORLD, key, actor, AuditAction.WORLD_CREATED,
                "generator=" + form.generator() + " environment=" + form.environment());

        // A deviation from the project preset is worth recording explicitly.
        WorldDefaults expected = defaultsFor(project);
        if (!Objects.equals(expected.getGenerator(), form.generator())) {
            audit.recordChange(WORLD, key, actor, AuditAction.WORLD_CREATED,
                    "generator", expected.getGenerator(), form.generator());
        }
        return OperationResult.success(world);
    }
}
