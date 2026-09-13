package net.onelitefeather.ploceus.platform;

import net.onelitefeather.ploceus.domain.PloceusKey;

public record WorldCreationRequest(PloceusKey key, String worldName, String generator,
                                   String generatorSettings, String environment, String biome,
                                   boolean generateStructures, Long seed) { }
