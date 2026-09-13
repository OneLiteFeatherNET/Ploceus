package net.onelitefeather.ploceus.persistence;

import org.bukkit.configuration.ConfigurationSection;
import java.nio.file.Path;

public record DatabaseConfig(String type, String jdbcUrl, String user, String password, int poolSize) {

    public static DatabaseConfig fromBukkit(ConfigurationSection section, Path dataFolder) {
        String type = section.getString("type", "h2").toLowerCase(java.util.Locale.ROOT);
        int poolSize = section.getInt("pool-size", 6);
        return switch (type) {
            case "postgresql" -> new DatabaseConfig(
                    type,
                    "jdbc:postgresql://%s:%d/%s".formatted(
                            section.getString("host", "localhost"),
                            section.getInt("port", 5432),
                            section.getString("name", "ploceus")),
                    section.getString("user", "ploceus"),
                    section.getString("password", ""),
                    poolSize);
            case "h2" -> new DatabaseConfig(
                    type,
                    "jdbc:h2:" + dataFolder.resolve(section.getString("file", "data")).toAbsolutePath(),
                    "sa", "", poolSize);
            default -> throw new IllegalArgumentException(
                    "Unknown database type '" + type + "'; expected 'h2' or 'postgresql'");
        };
    }

    public String hibernateDialect() {
        return "postgresql".equals(type)
                ? "org.hibernate.dialect.PostgreSQLDialect"
                : "org.hibernate.dialect.H2Dialect";
    }

    public String driverClass() {
        return "postgresql".equals(type) ? "org.postgresql.Driver" : "org.h2.Driver";
    }
}
