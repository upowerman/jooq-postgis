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

    private static final String JDBC_URL = TestDatabase.URL;
    private static final String JDBC_USER = TestDatabase.USER;
    private static final String JDBC_PASS = TestDatabase.PASSWORD;
    private static final String TARGET_DIR = "target/generated-test-sources/jooq";
    private static final String TARGET_PACKAGE = "top.yunitytech.maven.jooq.generated";

    @Test
    @DisplayName("Codegen Verification: jOOQ code generator generates valid Geometry fields with PostGIS bindings")
    void testJooqCodegenWithPostgisBindings() throws Exception {
        // 1. Verify DB connection and table schema.
        // A dedicated key-less table isolates codegen from primary-key introspection:
        // jOOQ 3.14's codegen looks up "key_seq" case-sensitively in the key metadata
        // result, which pgjdbc 42.7.5+ reports with uppercase ("KEY_SEQ") labels —
        // any table WITH a key fails to generate on that combination (jOOQ 3.15+ is
        // unaffected). The binding wiring under test does not depend on keys.
        try (Connection conn = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASS);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS postgis");
            stmt.execute("DROP TABLE IF EXISTS test_spatial_codegen");
            stmt.execute("CREATE TABLE test_spatial_codegen (" +
                    "  id BIGINT," +
                    "  name VARCHAR(100)," +
                    "  geom GEOMETRY(Geometry, 4326)," +
                    "  geog GEOGRAPHY(Point, 4326)," +
                    "  geog_z GEOGRAPHY(PointZ, 4326)," +
                    "  geog_m GEOGRAPHY(PointM, 4326)," +
                    "  geom_3d GEOMETRY(GeometryZ, 3857)," +
                    "  geom_m GEOMETRY(GeometryM, 4326)," +
                    "  geom_4d GEOMETRY(GeometryZM, 4326)," +
                    "  geom_any GEOMETRY" +
                    ")");
        } catch (Exception e) {
            Assumptions.abort("PostgreSQL/PostGIS is not reachable at " + JDBC_URL + ", skipping codegen test: " + e.getMessage());
        }

        // 2. Configure jOOQ Codegen Configuration programmatically.
        // jOOQ 3.15+ reports geometry columns as its native org.jooq.Geometry type, so the
        // bindings must be instantiated through the generic-binding protocol; jOOQ 3.14
        // resolves PostGIS columns as OTHER and instantiates the bindings raw.
        boolean supportsGenericBinding = supportsGenericBinding();
        ForcedType geometryForcedType = new ForcedType()
                .withUserType("org.locationtech.jts.geom.Geometry")
                .withBinding("top.yunitytech.maven.jooq.binding.PostgisGeometryBinding")
                .withIncludeTypes("(?i:geometry)");
        ForcedType geographyForcedType = new ForcedType()
                .withUserType("org.locationtech.jts.geom.Geometry")
                .withBinding("top.yunitytech.maven.jooq.binding.PostgisGeographyBinding")
                .withIncludeTypes("(?i:geography)");
        if (supportsGenericBinding) {
            enableGenericBinding(geometryForcedType);
            enableGenericBinding(geographyForcedType);
        }

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
                                .withIncludes("test_spatial_codegen")
                                .withForcedTypes(geometryForcedType, geographyForcedType))
                        .withTarget(new Target()
                                .withPackageName(TARGET_PACKAGE)
                                .withDirectory(TARGET_DIR)));

        // 3. Run GenerationTool
        GenerationTool.generate(config);

        // 4. Verify generated source files exist
        Path tableFilePath = Paths.get(TARGET_DIR, "top/yunitytech/maven/jooq/generated/tables/TestSpatialCodegen.java");
        Path recordFilePath = Paths.get(TARGET_DIR, "top/yunitytech/maven/jooq/generated/tables/records/TestSpatialCodegenRecord.java");

        assertThat(tableFilePath).exists();
        assertThat(recordFilePath).exists();

        // 5. Verify contents of the generated Table class.
        // jOOQ 3.14 emits the simple JTS type name; jOOQ 3.15+ (generic binding protocol)
        // may emit the fully-qualified name, so both spellings are accepted.
        String tableSource = new String(Files.readAllBytes(tableFilePath), StandardCharsets.UTF_8);
        assertThat(tableSource)
                .contains("PostgisGeometryBinding")
                .contains("PostgisGeographyBinding");
        assertThat(containsAny(tableSource,
                "TableField<TestSpatialCodegenRecord, Geometry> GEOM",
                "TableField<TestSpatialCodegenRecord, org.locationtech.jts.geom.Geometry> GEOM"))
                .as("GEOM field declared with JTS Geometry type").isTrue();
        assertThat(containsAny(tableSource,
                "TableField<TestSpatialCodegenRecord, Geometry> GEOG",
                "TableField<TestSpatialCodegenRecord, org.locationtech.jts.geom.Geometry> GEOG"))
                .as("GEOG field declared with JTS Geometry type").isTrue();

        // 6. Verify contents of the generated Record class
        String recordSource = new String(Files.readAllBytes(recordFilePath), StandardCharsets.UTF_8);
        assertThat(containsAny(recordSource,
                "public void setGeom(Geometry value)",
                "public void setGeom(org.locationtech.jts.geom.Geometry value)"))
                .as("record setter uses JTS Geometry").isTrue();
        assertThat(containsAny(recordSource,
                "public Geometry getGeom()",
                "public org.locationtech.jts.geom.Geometry getGeom()"))
                .as("record getter returns JTS Geometry").isTrue();
        assertThat(containsAny(recordSource,
                "public void setGeog(Geometry value)",
                "public void setGeog(org.locationtech.jts.geom.Geometry value)"))
                .as("record setter uses JTS Geometry").isTrue();
        assertThat(containsAny(recordSource,
                "public Geometry getGeog()",
                "public org.locationtech.jts.geom.Geometry getGeog()"))
                .as("record getter returns JTS Geometry").isTrue();

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
        // Compile into an isolated directory (not target/test-classes): generated classes
        // compiled against one jOOQ version must not leak onto the test classpath of
        // subsequent runs against a different -Djooq.version (VerifyError on final methods).
        compilerArgs.add("target/generated-test-classes");
        compilerArgs.addAll(javaFiles);

        int exitCode = compiler.run(null, null, null, compilerArgs.toArray(new String[0]));
        assertThat(exitCode).as("Generated code should compile without errors").isEqualTo(0);

        // 8. Verify generated Record getter/setter and converter interaction via reflection
        try (java.net.URLClassLoader classLoader = new java.net.URLClassLoader(
                new java.net.URL[]{Paths.get("target/generated-test-classes").toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> recordClass = classLoader.loadClass(TARGET_PACKAGE + ".tables.records.TestSpatialCodegenRecord");
            Object recordInstance = recordClass.getDeclaredConstructor().newInstance();

            org.locationtech.jts.geom.GeometryFactory gf = PostgisCodec.GEOMETRY_FACTORY;
            org.locationtech.jts.geom.Point point = gf.createPoint(new org.locationtech.jts.geom.Coordinate(10.0, 20.0));
            point.setSRID(4326);

            java.lang.reflect.Method setGeom = recordClass.getMethod("setGeom", org.locationtech.jts.geom.Geometry.class);
            setGeom.invoke(recordInstance, point);

            java.lang.reflect.Method getGeom = recordClass.getMethod("getGeom");
            Object returned = getGeom.invoke(recordInstance);

            assertThat(returned).isInstanceOf(org.locationtech.jts.geom.Geometry.class);
            org.locationtech.jts.geom.Geometry returnedGeom = (org.locationtech.jts.geom.Geometry) returned;
            assertThat(returnedGeom.getCoordinate().x).isEqualTo(10.0);
            assertThat(returnedGeom.getCoordinate().y).isEqualTo(20.0);
            assertThat(returnedGeom.getSRID()).isEqualTo(4326);
        }
    }

    /**
     * {@code <genericBinding>} was introduced in jOOQ 3.15; this project compiles against
     * 3.14, so presence is probed and invoked reflectively.
     */
    private static boolean supportsGenericBinding() {
        try {
            ForcedType.class.getMethod("setGenericBinding", Boolean.class);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static void enableGenericBinding(ForcedType forcedType) {
        try {
            ForcedType.class.getMethod("setGenericBinding", Boolean.class).invoke(forcedType, Boolean.TRUE);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to enable generic binding", e);
        }
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
