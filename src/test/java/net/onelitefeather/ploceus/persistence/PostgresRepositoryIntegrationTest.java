package net.onelitefeather.ploceus.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/// Runs the same shared repository assertions as {@link H2RepositoryIntegrationTest} against
/// real PostgreSQL for dialect fidelity. This class is skipped rather than failed when no
/// Docker daemon is available, since Testcontainers cannot start without one.
@Testcontainers(disabledWithoutDocker = true)
class PostgresRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static SessionFactory sessionFactory;
    static HikariDataSource dataSource;
    static ProjectRepository projects;
    static WorldRepository worlds;
    static TagRepository tags;
    static AuditRepository audit;

    @BeforeAll
    static void setUp() {
        DatabaseConfig config = new DatabaseConfig("postgresql",
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), 4);
        dataSource = HibernateBootstrap.dataSource(config);
        ClassLoader loader = PostgresRepositoryIntegrationTest.class.getClassLoader();
        HibernateBootstrap.migrate(dataSource, loader);
        sessionFactory = HibernateBootstrap.create(config, dataSource, loader);
        projects = new ProjectRepository(sessionFactory);
        worlds = new WorldRepository(sessionFactory);
        tags = new TagRepository(sessionFactory);
        audit = new AuditRepository(sessionFactory);
    }

    @AfterAll
    static void tearDown() {
        if (sessionFactory != null) {
            sessionFactory.close();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void savesAndReadsBackAProjectWithTagsAndAuthors() {
        RepositoryIntegrationAssertions.savesAndReadsBackAProjectWithTagsAndAuthors(projects, tags);
    }

    @Test
    void aWorldWithoutAProjectIsFoundAsUnassigned() {
        RepositoryIntegrationAssertions.aWorldWithoutAProjectIsFoundAsUnassigned(worlds);
    }

    @Test
    void searchMatchesOnTag() {
        RepositoryIntegrationAssertions.searchMatchesOnTag(projects, tags);
    }

    @Test
    void bulkVisitUpdateWritesEveryEntry() {
        RepositoryIntegrationAssertions.bulkVisitUpdateWritesEveryEntry(worlds);
    }

    @Test
    void aProjectCarriesItsWorldCountAfterTheSessionClosed() {
        RepositoryIntegrationAssertions.aProjectCarriesItsWorldCountAfterTheSessionClosed(projects, worlds);
    }

    @Test
    void auditEntriesComeBackNewestFirst() {
        RepositoryIntegrationAssertions.auditEntriesComeBackNewestFirst(audit);
    }
}
