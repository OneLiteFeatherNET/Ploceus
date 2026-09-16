package net.onelitefeather.ploceus.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// Runs the shared repository assertions against an in-memory H2 database. Requires no
/// external service, so it always runs — this is what proves the Liquibase changelog and
/// the Hibernate mapping agree in every environment, Docker or not.
class H2RepositoryIntegrationTest {

    static SessionFactory sessionFactory;
    static HikariDataSource dataSource;
    static ProjectRepository projects;
    static WorldRepository worlds;
    static TagRepository tags;
    static AuditRepository audit;

    @BeforeAll
    static void setUp() {
        DatabaseConfig config = new DatabaseConfig(
                "h2", "jdbc:h2:mem:ploceus;DB_CLOSE_DELAY=-1", "sa", "", 4);
        dataSource = HibernateBootstrap.dataSource(config);
        ClassLoader loader = H2RepositoryIntegrationTest.class.getClassLoader();
        HibernateBootstrap.migrate(dataSource, loader);
        sessionFactory = HibernateBootstrap.create(config, dataSource, loader);
        projects = new ProjectRepository(sessionFactory);
        worlds = new WorldRepository(sessionFactory);
        tags = new TagRepository(sessionFactory);
        audit = new AuditRepository(sessionFactory);
    }

    @AfterAll
    static void tearDown() {
        sessionFactory.close();
        dataSource.close();
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
