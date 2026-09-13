package net.onelitefeather.ploceus.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import net.onelitefeather.ploceus.domain.*;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

import java.sql.Connection;
import java.util.Map;

public final class HibernateBootstrap {

    private static final String CHANGELOG = "db/changelog/db.changelog-master.xml";

    private HibernateBootstrap() { }

    public static HikariDataSource dataSource(DatabaseConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.user());
        hikari.setPassword(config.password());
        hikari.setDriverClassName(config.driverClass());
        hikari.setMaximumPoolSize(config.poolSize());
        hikari.setPoolName("ploceus-pool");
        return new HikariDataSource(hikari);
    }

    /// Applies pending Liquibase changesets. Must run before {@link #create}, because
    /// Hibernate is configured to validate rather than create the schema.
    public static void migrate(HikariDataSource dataSource, ClassLoader pluginClassLoader) {
        withClassLoader(pluginClassLoader, () -> {
            try (Connection connection = dataSource.getConnection()) {
                var database = DatabaseFactory.getInstance()
                        .findCorrectDatabaseImplementation(new JdbcConnection(connection));
                try (Liquibase liquibase = new Liquibase(
                        CHANGELOG, new ClassLoaderResourceAccessor(pluginClassLoader), database)) {
                    liquibase.update("");
                }
            } catch (Exception exception) {
                throw new IllegalStateException("Ploceus schema migration failed", exception);
            }
            return null;
        });
    }

    public static SessionFactory create(DatabaseConfig config, HikariDataSource dataSource,
                                        ClassLoader pluginClassLoader) {
        return withClassLoader(pluginClassLoader, () -> {
            Configuration configuration = new Configuration()
                    .addAnnotatedClass(Project.class)
                    .addAnnotatedClass(ProjectWorld.class)
                    .addAnnotatedClass(Tag.class)
                    .addAnnotatedClass(AuditEntry.class)
                    .addProperties(properties(config));
            // A JPA AttributeConverter cannot apply to @Id attributes (PloceusKey is the id
            // of Project, ProjectWorld and Tag), so PloceusKey is registered as a genuine
            // Hibernate basic type instead - see KeyUserType for why.
            configuration.registerTypeOverride(new KeyUserType(), new String[] {PloceusKey.class.getName()});
            // Properties extends Hashtable<Object, Object>, so it also carries the live
            // HikariDataSource - Configuration#setProperty only accepts String/primitive values.
            configuration.getProperties().put("hibernate.connection.datasource", dataSource);
            return configuration.buildSessionFactory();
        });
    }

    private static java.util.Properties properties(DatabaseConfig config) {
        java.util.Properties properties = new java.util.Properties();
        Map.of(
                "hibernate.dialect", config.hibernateDialect(),
                "hibernate.hbm2ddl.auto", "validate",
                "hibernate.show_sql", "false",
                "hibernate.format_sql", "false",
                "hibernate.jdbc.batch_size", "32",
                "hibernate.order_updates", "true"
        ).forEach(properties::setProperty);
        return properties;
    }

    private static <T> T withClassLoader(ClassLoader classLoader, java.util.function.Supplier<T> action) {
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        current.setContextClassLoader(classLoader);
        try {
            return action.get();
        } finally {
            current.setContextClassLoader(previous);
        }
    }
}
