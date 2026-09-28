package top.yunitytech.maven.jooq.binding;

import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PostgisCodegenIntegrationTest {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5432/test_db";
    private static final String JDBC_USER = "postgres";
    private static final String JDBC_PASS = "postgres";
    private static final String TARGET_DIR = "target/generated-test-sources/jooq";
    private static final String TARGET_PACKAGE = "top.yunitytech.maven.jooq.generated";

    @Test
    @DisplayName("Codegen Verification: jOOQ code generator generates valid Geometry fields with PostGIS bindings")
    void testJooqCodegenWithPostgisBindings() throws Exception {
        // 1. Verify DB connection and table schema
        try (Connection conn = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASS);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS postgis");
            stmt.execute("CREATE TABLE IF NOT EXISTS test_spatial (" +
                    "  id BIGSERIAL PRIMARY KEY," +
                    "  name VARCHAR(100)," +
                    "  geom GEOMETRY(Geometry, 4326)," +
                    "  geog GEOGRAPHY(Point, 4326)," +
                    "  geom_3d GEOMETRY(GeometryZ, 3857)," +
                    "  geom_m GEOMETRY(GeometryM, 4326)," +
                    "  geom_4d GEOMETRY(GeometryZM, 4326)," +
                    "  geom_any GEOMETRY" +
                    ")");
        } catch (Exception e) {
            Assumptions.abort("PostgreSQL/PostGIS is not reachable at " + JDBC_URL + ", skipping codegen test: " + e.getMessage());
        }

        // 2. Configure jOOQ Codegen Configuration programmatically
        Configuration config = new Configuration()
                .withLogging(Logging.DEBUG)
                .withJdbc(new Jdbc()
                        .withDriver("org.postgresql.Driver")
                        .withUrl(JDBC_URL)
                        .withUser(JDBC_USER)
                        .withPassword(JDBC_PASS))
                .withGenerator(new Generator()
                        .withGenerate(new Generate()
                                .withRelations(false)
                                .withTables(true)
                                .withRecords(true))
                        .withDatabase(new Database()
                                .withName("org.jooq.meta.postgres.PostgresDatabase")
                                .withInputSchema("public")
                                .withIncludes("test_spatial")
                                .withForcedTypes(
                                        new ForcedType()
                                                .withUserType("org.locationtech.jts.geom.Geometry")
                                                .withBinding("top.yunitytech.maven.jooq.binding.PostgisGeometryBinding")
                                                .withIncludeTypes("(?i:geometry)"),
                                        new ForcedType()
                                                .withUserType("org.locationtech.jts.geom.Geometry")
                                                .withBinding("top.yunitytech.maven.jooq.binding.PostgisGeographyBinding")
                                                .withIncludeTypes("(?i:geography)")
                                ))
                        .withTarget(new Target()
                                .withPackageName(TARGET_PACKAGE)
                                .withDirectory(TARGET_DIR)));

        // 3. Run GenerationTool
        GenerationTool.generate(config);

        // 4. Verify generated source files exist
        Path tableFilePath = Paths.get(TARGET_DIR, "top/yunitytech/maven/jooq/generated/tables/TestSpatial.java");
        Path recordFilePath = Paths.get(TARGET_DIR, "top/yunitytech/maven/jooq/generated/tables/records/TestSpatialRecord.java");

        assertThat(tableFilePath).exists();
        assertThat(recordFilePath).exists();

        // 5. Verify contents of generated Table class
        String tableSource = new String(Files.readAllBytes(tableFilePath), StandardCharsets.UTF_8);
        assertThat(tableSource)
                .contains("TableField<TestSpatialRecord, Geometry> GEOM")
                .contains("TableField<TestSpatialRecord, Geometry> GEOG")
                .contains("top.yunitytech.maven.jooq.binding.PostgisGeometryBinding")
                .contains("top.yunitytech.maven.jooq.binding.PostgisGeographyBinding");

        // 6. Verify contents of generated Record class
        String recordSource = new String(Files.readAllBytes(recordFilePath), StandardCharsets.UTF_8);
        assertThat(recordSource)
                .contains("public void setGeom(Geometry value)")
                .contains("public Geometry getGeom()")
                .contains("public void setGeog(Geometry value)")
                .contains("public Geometry getGeog()");

        // 7. Dynamically compile generated code to ensure zero compilation errors
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("System JavaCompiler should be available").isNotNull();

        List<String> javaFiles;
        try (Stream<Path> stream = Files.walk(Paths.get(TARGET_DIR))) {
            javaFiles = stream.filter(p -> p.toString().endsWith(".java"))
                    .map(Path::toString)
                    .collect(Collectors.toList());
        }

        assertThat(javaFiles).isNotEmpty();

        String classpath = System.getProperty("java.class.path");
        List<String> compilerArgs = new ArrayList<>();
        compilerArgs.add("-cp");
        compilerArgs.add(classpath);
        compilerArgs.add("-d");
        compilerArgs.add("target/test-classes");
        compilerArgs.addAll(javaFiles);

        int exitCode = compiler.run(null, null, null, compilerArgs.toArray(new String[0]));
        assertThat(exitCode).as("Generated code should compile without errors").isEqualTo(0);
    }
}
