package net.onelitefeather.ploceus.domain;

import java.util.UUID;

/// Who performed an action. Authors are credits; the actor is who changed something.
public record Actor(UUID uuid, String name) {

    public static final Actor CONSOLE = new Actor(null, "CONSOLE");
}
