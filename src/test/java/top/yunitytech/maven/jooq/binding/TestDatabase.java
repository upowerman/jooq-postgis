package top.yunitytech.maven.jooq.binding;

/**
 * Integration test database configuration. Values resolve in order:
 * system property {@code test.db.url|user|password} → environment variable
 * {@code TEST_DB_URL|TEST_DB_USER|TEST_DB_PASSWORD} → local Docker default
 * ({@code jdbc:postgresql://localhost:5432/test_db}, postgres/postgres).
 * <p>
 * Integration tests abort (skip) when the database is not reachable, so a plain
 * {@code mvn test} on a machine without PostGIS still succeeds — CI runs the same
 * tests against a {@code postgis/postgis} service container via these variables.
 */
final class TestDatabase {

    static final String URL = resolve("test.db.url", "TEST_DB_URL", "jdbc:postgresql://localhost:5432/test_db");
    static final String USER = resolve("test.db.user", "TEST_DB_USER", "postgres");
    static final String PASSWORD = resolve("test.db.password", "TEST_DB_PASSWORD", "postgres");

    private TestDatabase() {
    }

    private static String resolve(String systemProperty, String envVariable, String fallback) {
        String value = System.getProperty(systemProperty);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        value = System.getenv(envVariable);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        return fallback;
    }
}
