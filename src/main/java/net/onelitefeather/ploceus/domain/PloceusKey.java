package net.onelitefeather.ploceus.domain;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;

/// Identity of every addressable Ploceus entity.
///
/// The namespace is fixed: users type only the value, and the namespace is prepended here.
public record PloceusKey(Key key) implements Keyed, Comparable<PloceusKey> {

    public static final String NAMESPACE = "ploceus";

    public PloceusKey {
        if (!NAMESPACE.equals(key.namespace())) {
            throw new IllegalArgumentException(
                    "Expected namespace '" + NAMESPACE + "' but got '" + key.namespace() + "'");
        }
    }

    public static PloceusKey of(String value) {
        if (!isValidValue(value)) {
            throw new IllegalArgumentException("Not a valid key value: '" + value + "'");
        }
        return new PloceusKey(Key.key(NAMESPACE, value));
    }

    /// Derives a key from a Multiverse world name. Never throws: illegal characters
    /// become '_' so that every existing world can be imported.
    public static PloceusKey fromWorldName(String worldName) {
        StringBuilder builder = new StringBuilder(worldName.length());
        for (char c : worldName.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            builder.append(Key.allowedInValue(c) ? c : '_');
        }
        String value = builder.isEmpty() ? "world" : builder.toString();
        return of(value);
    }

    public static PloceusKey parse(String stored) {
        return new PloceusKey(Key.key(stored));
    }

    public static boolean isValidValue(String value) {
        return value != null && !value.isEmpty() && Key.parseableValue(value);
    }

    public String value() {
        return key.value();
    }

    public String asString() {
        return key.asString();
    }

    @Override
    public int compareTo(PloceusKey other) {
        return key.compareTo(other.key);
    }

    @Override
    public String toString() {
        return asString();
    }
}
