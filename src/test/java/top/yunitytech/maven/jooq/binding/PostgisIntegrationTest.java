package top.yunitytech.maven.jooq.binding;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.WKTReader;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgisIntegrationTest {

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5432/test_db";
    private static final String JDBC_USER = "postgres";
    private static final String JDBC_PASS = "postgres";

    private Connection connection;
    private DSLContext dsl;
    private final GeometryFactory gf = new GeometryFactory();

    // Define table and fields with PostgisGeometryBinding
    private final Table<?> TEST_SPATIAL = DSL.table("test_spatial");
    private final Field<Long> ID = DSL.field("id", SQLDataType.BIGINT);
    private final Field<String> NAME = DSL.field("name", SQLDataType.VARCHAR);
    private final Field<Geometry> GEOM = DSL.field("geom", SQLDataType.OTHER.asConvertedDataType(new PostgisGeometryBinding()));
    private final Field<Geometry> GEOG = DSL.field("geog", SQLDataType.OTHER.asConvertedDataType(new PostgisGeometryBinding()));
    private final Field<Geometry> GEOM_3D = DSL.field("geom_3d", SQLDataType.OTHER.asConvertedDataType(new PostgisGeometryBinding()));

    @BeforeAll
    void initDatabase() {
        try {
            connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASS);
            dsl = DSL.using(connection, SQLDialect.POSTGRES);

            dsl.execute("CREATE EXTENSION IF NOT EXISTS postgis");
            dsl.execute("DROP TABLE IF EXISTS test_spatial");
            dsl.execute(
                    "CREATE TABLE test_spatial (" +
                            "  id BIGSERIAL PRIMARY KEY," +
                            "  name VARCHAR(100)," +
                            "  geom GEOMETRY(Geometry, 4326)," +
                            "  geog GEOGRAPHY(Point, 4326)," +
                            "  geom_3d GEOMETRY(GeometryZ, 3857)" +
                            ")"
            );
        } catch (SQLException e) {
            Assumptions.abort("PostGIS not available at " + JDBC_URL + ", skipping integration tests. Reason: " + e.getMessage());
        }
    }

    @AfterAll
    void closeDatabase() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    @BeforeEach
    void cleanTable() {
        dsl.deleteFrom(TEST_SPATIAL).execute();
    }

    @Test
    @DisplayName("Real DB: Insert and select 2D Point (EPSG:4326)")
    void testInsertAndSelectPoint2D() {
        Point point = gf.createPoint(new Coordinate(116.4074, 39.9042));
        point.setSRID(4326);

        int rows = dsl.insertInto(TEST_SPATIAL)
                .set(ID, 1L)
                .set(NAME, "Beijing")
                .set(GEOM, point)
                .execute();
        assertThat(rows).isEqualTo(1);

        Geometry result = dsl.select(GEOM)
                .from(TEST_SPATIAL)
                .where(ID.eq(1L))
                .fetchOne(GEOM);

        assertThat(result).isNotNull().isInstanceOf(Point.class);
        assertThat(result.getSRID()).isEqualTo(4326);
        assertThat(result.getCoordinate().x).isEqualTo(116.4074);
        assertThat(result.getCoordinate().y).isEqualTo(39.9042);
    }

    @Test
    @DisplayName("Real DB: Insert and select Polygon (EPSG:4326)")
    void testInsertAndSelectPolygon() {
        Coordinate[] ring = new Coordinate[]{
                new Coordinate(116.0, 39.0),
                new Coordinate(117.0, 39.0),
                new Coordinate(117.0, 40.0),
                new Coordinate(116.0, 40.0),
                new Coordinate(116.0, 39.0)
        };
        Polygon polygon = gf.createPolygon(ring);
        polygon.setSRID(4326);

        dsl.insertInto(TEST_SPATIAL)
                .set(ID, 2L)
                .set(NAME, "Area")
                .set(GEOM, polygon)
                .execute();

        Geometry result = dsl.select(GEOM)
                .from(TEST_SPATIAL)
                .where(ID.eq(2L))
                .fetchOne(GEOM);

        assertThat(result).isNotNull().isInstanceOf(Polygon.class);
        assertThat(result.getSRID()).isEqualTo(4326);
        assertThat(result.equalsExact(polygon)).isTrue();
    }

    @Test
    @DisplayName("Real DB: Insert and select 3D Point (EPSG:3857 PointZ)")
    void testInsertAndSelectPoint3D() {
        Point point3d = gf.createPoint(new Coordinate(12958742.0, 4851892.0, 50.0));
        point3d.setSRID(3857);

        dsl.insertInto(TEST_SPATIAL)
                .set(ID, 3L)
                .set(NAME, "3D Landmark")
                .set(GEOM_3D, point3d)
                .execute();

        Geometry result = dsl.select(GEOM_3D)
                .from(TEST_SPATIAL)
                .where(ID.eq(3L))
                .fetchOne(GEOM_3D);

        assertThat(result).isNotNull().isInstanceOf(Point.class);
        assertThat(result.getSRID()).isEqualTo(3857);
        assertThat(result.getCoordinate().x).isEqualTo(12958742.0);
        assertThat(result.getCoordinate().y).isEqualTo(4851892.0);
        assertThat(result.getCoordinate().getZ()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("Real DB: Insert and select Geography column")
    void testInsertAndSelectGeography() {
        Point geogPoint = gf.createPoint(new Coordinate(121.4737, 31.2304));
        geogPoint.setSRID(4326);

        dsl.insertInto(TEST_SPATIAL)
                .set(ID, 4L)
                .set(NAME, "Shanghai")
                .set(GEOG, geogPoint)
                .execute();

        Geometry result = dsl.select(GEOG)
                .from(TEST_SPATIAL)
                .where(ID.eq(4L))
                .fetchOne(GEOG);

        assertThat(result).isNotNull().isInstanceOf(Point.class);
        assertThat(result.getSRID()).isEqualTo(4326);
        assertThat(result.getCoordinate().x).isEqualTo(121.4737);
        assertThat(result.getCoordinate().y).isEqualTo(31.2304);
    }

    @Test
    @DisplayName("Real DB: Insert and select NULL spatial values")
    void testInsertAndSelectNull() {
        dsl.insertInto(TEST_SPATIAL)
                .set(ID, 5L)
                .set(NAME, "Null Geometry")
                .set(GEOM, (Geometry) null)
                .execute();

        Geometry result = dsl.select(GEOM)
                .from(TEST_SPATIAL)
                .where(ID.eq(5L))
                .fetchOne(GEOM);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Real DB: Batch insert mixing NULL and non-null values")
    void testBatchInsert() {
        Point p1 = gf.createPoint(new Coordinate(116.0, 39.0));
        p1.setSRID(4326);

        Point p2 = gf.createPoint(new Coordinate(117.0, 39.0));
        p2.setSRID(4326);

        dsl.batch(
                dsl.insertInto(TEST_SPATIAL).set(ID, 10L).set(NAME, "Batch 1").set(GEOM, p1),
                dsl.insertInto(TEST_SPATIAL).set(ID, 11L).set(NAME, "Batch 2 (null)").set(GEOM, (Geometry) null),
                dsl.insertInto(TEST_SPATIAL).set(ID, 12L).set(NAME, "Batch 3").set(GEOM, p2)
        ).execute();

        assertThat(dsl.selectCount().from(TEST_SPATIAL).fetchOne(0, Integer.class)).isEqualTo(3);

        Geometry r1 = dsl.select(GEOM).from(TEST_SPATIAL).where(ID.eq(10L)).fetchOne(GEOM);
        Geometry r2 = dsl.select(GEOM).from(TEST_SPATIAL).where(ID.eq(11L)).fetchOne(GEOM);
        Geometry r3 = dsl.select(GEOM).from(TEST_SPATIAL).where(ID.eq(12L)).fetchOne(GEOM);

        assertThat(r1).isNotNull();
        assertThat(r2).isNull();
        assertThat(r3).isNotNull();
    }

    @Test
    @DisplayName("Real DB: PostGIS SQL functions (ST_DistanceSphere, ST_Transform)")
    void testPostgisFunctions() {
        // Beijing
        Point beijing = gf.createPoint(new Coordinate(116.4074, 39.9042));
        beijing.setSRID(4326);

        // Tianjin
        Point tianjin = gf.createPoint(new Coordinate(117.2008, 39.0840));
        tianjin.setSRID(4326);

        dsl.insertInto(TEST_SPATIAL).set(ID, 20L).set(NAME, "Beijing").set(GEOM, beijing).execute();
        dsl.insertInto(TEST_SPATIAL).set(ID, 21L).set(NAME, "Tianjin").set(GEOM, tianjin).execute();

        // Calculate distance in meters using ST_Distance on geography
        Double distanceMeters = dsl.select(
                DSL.field("ST_Distance(a.geom::geography, b.geom::geography)", Double.class)
        ).from(TEST_SPATIAL.as("a"))
                .crossJoin(TEST_SPATIAL.as("b"))
                .where(DSL.field("a.id", Long.class).eq(20L))
                .and(DSL.field("b.id", Long.class).eq(21L))
                .fetchOne(0, Double.class);

        // Distance between Beijing and Tianjin is roughly 110-120 km (~114,000 meters)
        assertThat(distanceMeters).isNotNull();
        assertThat(distanceMeters).isBetween(100_000.0, 130_000.0);
    }

    @Test
    @DisplayName("Complex Geometry: Polygon with multiple interior rings (holes)")
    void testPolygonWithMultipleHoles() throws Exception {
        // Outer box 100x100, Hole 1 (10,10 to 30,30), Hole 2 (50,50 to 80,80)
        String wkt = "POLYGON((" +
                "0 0, 100 0, 100 100, 0 100, 0 0" +
                "), (" +
                "10 10, 30 10, 30 30, 10 30, 10 10" +
                "), (" +
                "50 50, 80 50, 80 80, 50 80, 50 50" +
                "))";

        Geometry parsed = new WKTReader(gf).read(wkt);
        parsed.setSRID(4326);

        dsl.insertInto(TEST_SPATIAL)
                .set(ID, 30L)
                .set(NAME, "Polygon With 2 Holes")
                .set(GEOM, parsed)
                .execute();

        Geometry result = dsl.select(GEOM).from(TEST_SPATIAL).where(ID.eq(30L)).fetchOne(GEOM);

        assertThat(result).isNotNull().isInstanceOf(Polygon.class);
        Polygon polygon = (Polygon) result;
        assertThat(polygon.getSRID()).isEqualTo(4326);
        assertThat(polygon.getNumInteriorRing()).isEqualTo(2);

        // Expected Area: 100*100 - 20*20 - 30*30 = 10000 - 400 - 900 = 8700
        assertThat(polygon.getArea()).isEqualTo(8700.0);

        // Verify database calculated ST_Area matches JTS calculated area
        Double dbArea = dsl.select(DSL.field("ST_Area(geom)", Double.class))
                .from(TEST_SPATIAL)
                .where(ID.eq(30L))
                .fetchOne(0, Double.class);
        assertThat(dbArea).isEqualTo(8700.0);
    }

    @Test
    @DisplayName("Complex Geometry: MultiPolygon with holes in components")
    void testMultiPolygonWithHoles() throws Exception {
        // MultiPolygon: Polygon 1 has a hole, Polygon 2 is solid
        String wkt = "MULTIPOLYGON((" +
                "(0 0, 50 0, 50 50, 0 50, 0 0), (10 10, 20 10, 20 20, 10 20, 10 10)" +
                "), (" +
                "(60 60, 100 60, 100 100, 60 100, 60 60)" +
                "))";

        Geometry multiGeom = new WKTReader(gf).read(wkt);
        multiGeom.setSRID(4326);

        dsl.insertInto(TEST_SPATIAL)
                .set(ID, 31L)
                .set(NAME, "MultiPolygon with Holes")
                .set(GEOM, multiGeom)
                .execute();

        Geometry result = dsl.select(GEOM).from(TEST_SPATIAL).where(ID.eq(31L)).fetchOne(GEOM);

        assertThat(result).isNotNull().isInstanceOf(MultiPolygon.class);
        MultiPolygon mp = (MultiPolygon) result;
        assertThat(mp.getSRID()).isEqualTo(4326);
        assertThat(mp.getNumGeometries()).isEqualTo(2);

        Polygon p1 = (Polygon) mp.getGeometryN(0);
        assertThat(p1.getNumInteriorRing()).isEqualTo(1);
        assertThat(p1.getArea()).isEqualTo(50 * 50 - 10 * 10); // 2400

        Polygon p2 = (Polygon) mp.getGeometryN(1);
        assertThat(p2.getNumInteriorRing()).isEqualTo(0);
        assertThat(p2.getArea()).isEqualTo(40 * 40); // 1600

        assertThat(mp.getArea()).isEqualTo(4000.0);
        assertThat(result.equalsExact(multiGeom)).isTrue();
    }

    @Test
    @DisplayName("Complex Geometry: High-density 360-vertex circle polygon")
    void testHighDensityPolygon() {
        int vertexCount = 360;
        Coordinate[] coords = new Coordinate[vertexCount + 1];
        double centerX = 116.4;
        double centerY = 39.9;
        double radius = 0.5;

        for (int i = 0; i < vertexCount; i++) {
            double angle = 2 * Math.PI * i / vertexCount;
            double x = centerX + radius * Math.cos(angle);
            double y = centerY + radius * Math.sin(angle);
            coords[i] = new Coordinate(x, y);
        }
        coords[vertexCount] = coords[0]; // Close the ring

        Polygon circlePolygon = gf.createPolygon(coords);
        circlePolygon.setSRID(4326);

        dsl.insertInto(TEST_SPATIAL)
                .set(ID, 32L)
                .set(NAME, "360-vertex Circle Polygon")
                .set(GEOM, circlePolygon)
                .execute();

        Geometry result = dsl.select(GEOM).from(TEST_SPATIAL).where(ID.eq(32L)).fetchOne(GEOM);

        assertThat(result).isNotNull().isInstanceOf(Polygon.class);
        Polygon fetched = (Polygon) result;
        assertThat(fetched.getCoordinates()).hasSize(361);
        assertThat(fetched.getSRID()).isEqualTo(4326);
        assertThat(fetched.equalsExact(circlePolygon)).isTrue();
    }

    @Test
    @DisplayName("Complex Geometry: 3D Polygon with hole (PolygonZ)")
    void test3DPolygonWithHole() throws Exception {
        String wkt = "POLYGON((" +
                "0 0 10, 100 0 15, 100 100 20, 0 100 15, 0 0 10" +
                "), (" +
                "20 20 12, 40 20 14, 40 40 16, 20 40 14, 20 20 12" +
                "))";

        Geometry parsed = new WKTReader(gf).read(wkt);
        parsed.setSRID(3857);

        dsl.insertInto(TEST_SPATIAL)
                .set(ID, 33L)
                .set(NAME, "3D Polygon With Hole")
                .set(GEOM_3D, parsed)
                .execute();

        Geometry result = dsl.select(GEOM_3D).from(TEST_SPATIAL).where(ID.eq(33L)).fetchOne(GEOM_3D);

        assertThat(result).isNotNull().isInstanceOf(Polygon.class);
        Polygon polygon = (Polygon) result;
        assertThat(polygon.getSRID()).isEqualTo(3857);
        assertThat(polygon.getNumInteriorRing()).isEqualTo(1);

        // Check 3D Z coordinate retention
        Coordinate coord = polygon.getExteriorRing().getCoordinateN(2);
        assertThat(coord.getZ()).isEqualTo(20.0);

        Coordinate holeCoord = polygon.getInteriorRingN(0).getCoordinateN(2);
        assertThat(holeCoord.getZ()).isEqualTo(16.0);
    }

    @Test
    @DisplayName("Complex Geometry: MultiLineString and MultiPoint")
    void testMultiLineStringAndMultiPoint() throws Exception {
        // MultiLineString
        String mlsWkt = "MULTILINESTRING((0 0, 10 10, 20 20), (30 30, 40 40, 50 60), (70 70, 80 90))";
        Geometry mls = new WKTReader(gf).read(mlsWkt);
        mls.setSRID(4326);

        dsl.insertInto(TEST_SPATIAL).set(ID, 34L).set(NAME, "MultiLine").set(GEOM, mls).execute();

        Geometry mlsResult = dsl.select(GEOM).from(TEST_SPATIAL).where(ID.eq(34L)).fetchOne(GEOM);
        assertThat(mlsResult).isNotNull().isInstanceOf(MultiLineString.class);
        assertThat(((MultiLineString) mlsResult).getNumGeometries()).isEqualTo(3);
        assertThat(mlsResult.getSRID()).isEqualTo(4326);

        // MultiPoint
        String mpWkt = "MULTIPOINT((10 10), (20 20), (30 30), (40 40), (50 50))";
        Geometry mp = new WKTReader(gf).read(mpWkt);
        mp.setSRID(4326);

        dsl.insertInto(TEST_SPATIAL).set(ID, 35L).set(NAME, "MultiPoint").set(GEOM, mp).execute();

        Geometry mpResult = dsl.select(GEOM).from(TEST_SPATIAL).where(ID.eq(35L)).fetchOne(GEOM);
        assertThat(mpResult).isNotNull().isInstanceOf(MultiPoint.class);
        assertThat(((MultiPoint) mpResult).getNumGeometries()).isEqualTo(5);
        assertThat(mpResult.getSRID()).isEqualTo(4326);
    }

    @Test
    @DisplayName("Complex Geometry: Heterogeneous GeometryCollection")
    void testGeometryCollection() throws Exception {
        String gcWkt = "GEOMETRYCOLLECTION(" +
                "POINT(10 10), " +
                "LINESTRING(0 0, 5 5, 10 0), " +
                "POLYGON((20 20, 40 20, 40 40, 20 40, 20 20))" +
                ")";

        Geometry gc = new WKTReader(gf).read(gcWkt);
        gc.setSRID(4326);

        dsl.insertInto(TEST_SPATIAL).set(ID, 36L).set(NAME, "GeometryCollection").set(GEOM, gc).execute();

        Geometry gcResult = dsl.select(GEOM).from(TEST_SPATIAL).where(ID.eq(36L)).fetchOne(GEOM);
        assertThat(gcResult).isNotNull().isInstanceOf(GeometryCollection.class);
        GeometryCollection collection = (GeometryCollection) gcResult;
        assertThat(collection.getNumGeometries()).isEqualTo(3);
        assertThat(collection.getGeometryN(0)).isInstanceOf(Point.class);
        assertThat(collection.getGeometryN(1)).isInstanceOf(LineString.class);
        assertThat(collection.getGeometryN(2)).isInstanceOf(Polygon.class);
        assertThat(collection.getSRID()).isEqualTo(4326);
    }

    @Test
    @DisplayName("Complex Geometry: PostGIS topological query on polygon with holes (ST_Contains)")
    void testTopologicalQueryOnPolygonWithHoles() throws Exception {
        // Outer: (0 0 to 100 100), Hole: (40 40 to 60 60)
        String wkt = "POLYGON((0 0, 100 0, 100 100, 0 100, 0 0), (40 40, 60 40, 60 60, 40 60, 40 40))";
        Geometry polygonWithHole = new WKTReader(gf).read(wkt);
        polygonWithHole.setSRID(4326);

        dsl.insertInto(TEST_SPATIAL).set(ID, 37L).set(NAME, "Donut Polygon").set(GEOM, polygonWithHole).execute();

        // 1. Point (20, 20) is inside the outer ring and outside the hole -> ST_Contains must be TRUE
        Boolean containsSolid = dsl.select(
                DSL.field("ST_Contains(geom, ST_SetSRID(ST_Point(20, 20), 4326))", Boolean.class)
        ).from(TEST_SPATIAL).where(ID.eq(37L)).fetchOne(0, Boolean.class);
        assertThat(containsSolid).isTrue();

        // 2. Point (50, 50) is inside the hole -> ST_Contains must be FALSE!
        Boolean containsHole = dsl.select(
                DSL.field("ST_Contains(geom, ST_SetSRID(ST_Point(50, 50), 4326))", Boolean.class)
        ).from(TEST_SPATIAL).where(ID.eq(37L)).fetchOne(0, Boolean.class);
        assertThat(containsHole).isFalse();

        // 3. Point (150, 150) is outside the polygon -> ST_Contains must be FALSE
        Boolean containsOutside = dsl.select(
                DSL.field("ST_Contains(geom, ST_SetSRID(ST_Point(150, 150), 4326))", Boolean.class)
        ).from(TEST_SPATIAL).where(ID.eq(37L)).fetchOne(0, Boolean.class);
        assertThat(containsOutside).isFalse();
    }
}
