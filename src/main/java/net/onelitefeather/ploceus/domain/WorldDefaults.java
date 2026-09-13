package net.onelitefeather.ploceus.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/// Pre-fills the world creation dialog. Pre-filled, never enforced — anyone with creation
/// rights may override it, and the deviation is written to the audit log.
@Embeddable
public class WorldDefaults {

    @Column(name = "default_generator")
    private String generator;

    @Column(name = "default_generator_settings")
    private String generatorSettings;

    @Column(name = "default_environment")
    private String environment;

    @Column(name = "default_generate_structures")
    private boolean generateStructures;

    @Column(name = "default_biome")
    private String biome;

    protected WorldDefaults() { }

    public WorldDefaults(String generator, String generatorSettings, String environment,
                         boolean generateStructures, String biome) {
        this.generator = generator;
        this.generatorSettings = generatorSettings;
        this.environment = environment;
        this.generateStructures = generateStructures;
        this.biome = biome;
    }

    public String getGenerator() { return generator; }
    public String getGeneratorSettings() { return generatorSettings; }
    public String getEnvironment() { return environment; }
    public boolean isGenerateStructures() { return generateStructures; }
    public String getBiome() { return biome; }
}
