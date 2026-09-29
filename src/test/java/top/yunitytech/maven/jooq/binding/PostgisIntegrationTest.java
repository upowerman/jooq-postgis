package top.yunitytech.maven.jooq.binding;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;
import org.locationtech.jts.io.WKTReader;
import top.yunitytech.maven.jooq.binding.internal.DimensionAnalyzer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgisIntegrationTest {

    private static final String JDBC_URL = TestDatabase.URL;
    private static final String JDBC_USER = TestDatabase.USER;
    private static final String JDBC_PASS = TestDatabase.PASSWORD;

    private Connection connection;
    private DSLContext dsl;
    private final GeometryFactory gf = PostgisCodec.GEOMETRY_FACTORY;

    // Define table and fields with distinct PostgisGeometryBinding and PostgisGeographyBinding
    private final Table<?> TEST_SPATIAL = DSL.table("test_spatial");
    private final Field<Long> ID = DSL.field("id", SQLDataType.BIGINT);
    private final Field<String> NAME = DSL.field("name", SQLDataType.VARCHAR);
    private final Field<Geometry> GEOM = DSL.field("geom", SQLDataType.OTHER.asConvertedDataType(new PostgisGeometryBinding()));
    private final Field<Geometry> GEOG = DSL.field("geog", SQLDataType.OTHER.asConvertedDataType(new PostgisGeographyBinding()));
    private final Field<Geometry> GEOG_Z = DSL.field("geog_z", SQLDataType.OTHER.asConvertedDataType(new PostgisGeographyBinding()));
    private final Field<Geometry> GEOG_M = DSL.field("geog_m", SQLDataType.OTHER.asConvertedDataType(new PostgisGeographyBinding()));
    private final Field<Geometry> GEOM_3D = DSL.field("geom_3d", SQLDataType.OTHER.asConvertedDataType(new PostgisGeometryBinding()));
    private final Field<Geometry> GEOM_M = DSL.field("geom_m", SQLDataType.OTHER.asConvertedDataType(new PostgisGeometryBinding()));
    private final Field<Geometry> GEOM_4D = DSL.field("geom_4d", SQLDataType.OTHER.asConvertedDataType(new PostgisGeometryBinding()));
    private final Field<Geometry> GEOM_ANY = DSL.field("geom_any", SQLDataType.OTHER.asConvertedDataType(new PostgisGeometryBinding()));

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
                    "  geog_z GEOGRAPHY(PointZ, 4326)," +
                    "  geog_m GEOGRAPHY(PointM, 4326)," +
                            "  geom_3d GEOMETRY(GeometryZ, 3857)," +
                            "  geom_m GEOMETRY(GeometryM, 4326)," +
                            "  geom_4d GEOMETRY(GeometryZM, 4326)," +
                            "  geom_any GEOMETRY" +
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

    @Test
    @DisplayName("Dimension 4D: Insert and select Point ZM (EPSG:4326 XYZM)")
    void testInsertAndSelectPoint4D_ZM() {
        Point p4d = gf.createPoint(new CoordinateXYZM(116.4, 39.9, 100.0, 88.5));
        p4d.setSRID(4326);

        dsl.insertInto(TEST_SPATIAL)
                .set(ID, 50L)
                .set(NAME, "Point ZM Satellite Measurement")
                .set(GEOM_4D, p4d)
                .execute();

        Geometry result = dsl.select(GEOM_4D).from(TEST_SPATIAL).where(ID.eq(50L)).fetchOne(GEOM_4D);

        assertThat(result).isNotNull().isInstanceOf(Point.class);
        assertThat(result.getSRID()).isEqualTo(4326);
        Coordinate coord = result.getCoordinate();
        assertThat(coord.x).isEqualTo(116.4);
        assertThat(coord.y).isEqualTo(39.9);
        assertThat(coord.getZ()).isEqualTo(100.0);
        assertThat(coord.getM()).isEqualTo(88.5);
    }

    @Test
    @DisplayName("Dimension 3DM: Insert and select Point M (EPSG:4326 XYM)")
    void testInsertAndSelectPoint3DM_XYM() {
        Point p3dm = gf.createPoint(new CoordinateXYM(116.4, 39.9, 77.2));
        p3dm.setSRID(4326);

        dsl.insertInto(TEST_SPATIAL)
                .set(ID, 51L)
                .set(NAME, "Point M Measurement")
                .set(GEOM_M, p3dm)
                .execute();

        Geometry result = dsl.select(GEOM_M).from(TEST_SPATIAL).where(ID.eq(51L)).fetchOne(GEOM_M);

        assertThat(result).isNotNull().isInstanceOf(Point.class);
        assertThat(result.getSRID()).isEqualTo(4326);
        Coordinate coord = result.getCoordinate();
        assertThat(coord.x).isEqualTo(116.4);
        assertThat(coord.y).isEqualTo(39.9);
        assertThat(Double.isNaN(coord.getZ())).isTrue();
        assertThat(coord.getM()).isEqualTo(77.2);
    }

    @Test
    @DisplayName("Geography: Native spherical distance and ST_DWithin without type casting")
    void testNativeGeographyDistanceAndDWithin() {
        // Shanghai
        Point shanghai = gf.createPoint(new Coordinate(121.4737, 31.2304));
        shanghai.setSRID(4326);

        // Hangzhou (~160 km away)
        Point hangzhou = gf.createPoint(new Coordinate(120.1551, 30.2741));
        hangzhou.setSRID(4326);

        dsl.insertInto(TEST_SPATIAL).set(ID, 60L).set(NAME, "Shanghai").set(GEOG, shanghai).execute();
        dsl.insertInto(TEST_SPATIAL).set(ID, 61L).set(NAME, "Hangzhou").set(GEOG, hangzhou).execute();

        // Native ST_Distance on geography columns (returns distance in meters)
        Double distanceMeters = dsl.select(
                DSL.field("ST_Distance(a.geog, b.geog)", Double.class)
        ).from(TEST_SPATIAL.as("a"))
                .crossJoin(TEST_SPATIAL.as("b"))
                .where(DSL.field("a.id", Long.class).eq(60L))
                .and(DSL.field("b.id", Long.class).eq(61L))
                .fetchOne(0, Double.class);

        assertThat(distanceMeters).isNotNull();
        // Distance is ~160-175 km (160,000 - 175,000 meters)
        assertThat(distanceMeters).isBetween(150_000.0, 180_000.0);

        // Native ST_DWithin on geography columns (threshold 200 km)
        Boolean within200km = dsl.select(
                DSL.field("ST_DWithin(a.geog, b.geog, 200000)", Boolean.class)
        ).from(TEST_SPATIAL.as("a"))
                .crossJoin(TEST_SPATIAL.as("b"))
                .where(DSL.field("a.id", Long.class).eq(60L))
                .and(DSL.field("b.id", Long.class).eq(61L))
                .fetchOne(0, Boolean.class);

        assertThat(within200km).isTrue();
    }

    @Test
    @DisplayName("Real DB: CGCS2000 (EPSG:4490) remote sensing spatial data round-trip")
    void testCGCS2000RemoteSensingData() {
        // Point in Beijing using CGCS2000 (SRID 4490)
        Point bj4490 = gf.createPoint(new Coordinate(116.4074, 39.9042));
        bj4490.setSRID(4490);

        dsl.insertInto(TEST_SPATIAL)
                .set(ID, 70L)
                .set(NAME, "Beijing CGCS2000")
                .set(GEOM_ANY, bj4490)
                .execute();

        Geometry resPoint = dsl.select(GEOM_ANY).from(TEST_SPATIAL).where(ID.eq(70L)).fetchOne(GEOM_ANY);
        assertThat(resPoint).isNotNull().isInstanceOf(Point.class);
        assertThat(resPoint.getSRID()).isEqualTo(4490);
        assertThat(resPoint.getCoordinate().x).isEqualTo(116.4074);
        assertThat(resPoint.getCoordinate().y).isEqualTo(39.9042);

        // Satellite observation footprint polygon in CGCS2000
        Coordinate[] footprint = new Coordinate[]{
                new Coordinate(115.0, 39.0), new Coordinate(117.0, 39.0),
                new Coordinate(117.0, 41.0), new Coordinate(115.0, 41.0),
                new Coordinate(115.0, 39.0)
        };
        Polygon poly4490 = gf.createPolygon(footprint);
        poly4490.setSRID(4490);

        dsl.insertInto(TEST_SPATIAL).set(ID, 71L).set(NAME, "AOI CGCS2000").set(GEOM_ANY, poly4490).execute();

        Geometry resPoly = dsl.select(GEOM_ANY).from(TEST_SPATIAL).where(ID.eq(71L)).fetchOne(GEOM_ANY);
        assertThat(resPoly).isNotNull().isInstanceOf(Polygon.class);
        assertThat(resPoly.getSRID()).isEqualTo(4490);
        assertThat(resPoly.equalsExact(poly4490)).isTrue();
    }

    @Test
    @DisplayName("Real DB: EMPTY geometries round-trip (Point EMPTY, Polygon EMPTY, GeometryCollection EMPTY)")
    void testEmptyGeometriesInDatabase() {
        Point emptyPoint = gf.createPoint();
        emptyPoint.setSRID(4326);

        Polygon emptyPoly = gf.createPolygon();
        emptyPoly.setSRID(3857);

        GeometryCollection emptyGC = gf.createGeometryCollection();
        emptyGC.setSRID(4490);

        dsl.insertInto(TEST_SPATIAL).set(ID, 80L).set(NAME, "Empty Point").set(GEOM_ANY, emptyPoint).execute();
        dsl.insertInto(TEST_SPATIAL).set(ID, 81L).set(NAME, "Empty Polygon").set(GEOM_ANY, emptyPoly).execute();
        dsl.insertInto(TEST_SPATIAL).set(ID, 82L).set(NAME, "Empty GC").set(GEOM_ANY, emptyGC).execute();

        Geometry rPoint = dsl.select(GEOM_ANY).from(TEST_SPATIAL).where(ID.eq(80L)).fetchOne(GEOM_ANY);
        assertThat(rPoint).isNotNull().isInstanceOf(Point.class);
        assertThat(rPoint.isEmpty()).isTrue();
        assertThat(rPoint.getSRID()).isEqualTo(4326);

        Geometry rPoly = dsl.select(GEOM_ANY).from(TEST_SPATIAL).where(ID.eq(81L)).fetchOne(GEOM_ANY);
        assertThat(rPoly).isNotNull().isInstanceOf(Polygon.class);
        assertThat(rPoly.isEmpty()).isTrue();
        assertThat(rPoly.getSRID()).isEqualTo(3857);

        Geometry rGC = dsl.select(GEOM_ANY).from(TEST_SPATIAL).where(ID.eq(82L)).fetchOne(GEOM_ANY);
        assertThat(rGC).isNotNull().isInstanceOf(GeometryCollection.class);
        assertThat(rGC.isEmpty()).isTrue();
        assertThat(rGC.getSRID()).isEqualTo(4490);
    }

    @Test
    @DisplayName("Real DB: Complex Polygon with hole and M / ZM coordinates")
    void testComplexPolygonWithHoleMAndZM() {
        // Polygon M (XYM) with hole
        CoordinateXYM[] shellM = new CoordinateXYM[]{
                new CoordinateXYM(0, 0, 1), new CoordinateXYM(0, 20, 2),
                new CoordinateXYM(20, 20, 3), new CoordinateXYM(20, 0, 4), new CoordinateXYM(0, 0, 1)
        };
        CoordinateXYM[] holeM = new CoordinateXYM[]{
                new CoordinateXYM(5, 5, 5), new CoordinateXYM(5, 10, 6),
                new CoordinateXYM(10, 10, 7), new CoordinateXYM(10, 5, 8), new CoordinateXYM(5, 5, 5)
        };
        Polygon polyM = gf.createPolygon(
                gf.createLinearRing(new CoordinateArraySequence(shellM)),
                new LinearRing[]{gf.createLinearRing(new CoordinateArraySequence(holeM))}
        );
        polyM.setSRID(4490);

        dsl.insertInto(TEST_SPATIAL).set(ID, 90L).set(NAME, "Polygon M with Hole").set(GEOM_ANY, polyM).execute();

        Geometry resPolyM = dsl.select(GEOM_ANY).from(TEST_SPATIAL).where(ID.eq(90L)).fetchOne(GEOM_ANY);
        assertThat(resPolyM).isNotNull().isInstanceOf(Polygon.class);
        Polygon rpM = (Polygon) resPolyM;
        assertThat(rpM.getSRID()).isEqualTo(4490);
        assertThat(rpM.getNumInteriorRing()).isEqualTo(1);
        Coordinate smc = rpM.getExteriorRing().getCoordinateN(0);
        assertThat(Double.isNaN(smc.getZ())).isTrue();
        assertThat(smc.getM()).isEqualTo(1.0);
        Coordinate hmc = rpM.getInteriorRingN(0).getCoordinateN(0);
        assertThat(Double.isNaN(hmc.getZ())).isTrue();
        assertThat(hmc.getM()).isEqualTo(5.0);

        // Polygon ZM (XYZM) with hole
        CoordinateXYZM[] shellZM = new CoordinateXYZM[]{
                new CoordinateXYZM(0, 0, 10, 1), new CoordinateXYZM(0, 20, 10, 2),
                new CoordinateXYZM(20, 20, 10, 3), new CoordinateXYZM(20, 0, 10, 4), new CoordinateXYZM(0, 0, 10, 1)
        };
        CoordinateXYZM[] holeZM = new CoordinateXYZM[]{
                new CoordinateXYZM(5, 5, 15, 5), new CoordinateXYZM(5, 10, 15, 6),
                new CoordinateXYZM(10, 10, 15, 7), new CoordinateXYZM(10, 5, 15, 8), new CoordinateXYZM(5, 5, 15, 5)
        };
        Polygon polyZM = gf.createPolygon(
                gf.createLinearRing(new CoordinateArraySequence(shellZM)),
                new LinearRing[]{gf.createLinearRing(new CoordinateArraySequence(holeZM))}
        );
        polyZM.setSRID(4490);

        dsl.insertInto(TEST_SPATIAL).set(ID, 91L).set(NAME, "Polygon ZM with Hole").set(GEOM_ANY, polyZM).execute();

        Geometry resPolyZM = dsl.select(GEOM_ANY).from(TEST_SPATIAL).where(ID.eq(91L)).fetchOne(GEOM_ANY);
        assertThat(resPolyZM).isNotNull().isInstanceOf(Polygon.class);
        Polygon rpZM = (Polygon) resPolyZM;
        assertThat(rpZM.getSRID()).isEqualTo(4490);
        assertThat(rpZM.getNumInteriorRing()).isEqualTo(1);
        Coordinate szmc = rpZM.getExteriorRing().getCoordinateN(0);
        assertThat(szmc.getZ()).isEqualTo(10.0);
        assertThat(szmc.getM()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Real DB: GeometryCollection with M and ZM components")
    void testGeometryCollectionMAndZM() {
        Point pM = gf.createPoint(new CoordinateXYM(10, 20, 30));
        LineString lsM = gf.createLineString(new CoordinateArraySequence(new CoordinateXYM[]{
                new CoordinateXYM(0, 0, 1), new CoordinateXYM(5, 5, 2)
        }));
        GeometryCollection gcM = gf.createGeometryCollection(new Geometry[]{pM, lsM});
        gcM.setSRID(4326);

        dsl.insertInto(TEST_SPATIAL).set(ID, 95L).set(NAME, "GC M").set(GEOM_ANY, gcM).execute();

        Geometry resGCM = dsl.select(GEOM_ANY).from(TEST_SPATIAL).where(ID.eq(95L)).fetchOne(GEOM_ANY);
        assertThat(resGCM).isNotNull().isInstanceOf(GeometryCollection.class);
        GeometryCollection rgcM = (GeometryCollection) resGCM;
        assertThat(rgcM.getNumGeometries()).isEqualTo(2);
        assertThat(rgcM.getGeometryN(0).getCoordinate().getM()).isEqualTo(30.0);
        assertThat(Double.isNaN(rgcM.getGeometryN(0).getCoordinate().getZ())).isTrue();
    }

    @Test
    @DisplayName("Real DB: Attempting to insert mixed-dimension GeometryCollection throws IllegalArgumentException")
    void testRejectMixedDimensionInsertion() {
        Point p2d = gf.createPoint(new Coordinate(1, 2));
        Point p3d = gf.createPoint(new Coordinate(3, 4, 5));
        GeometryCollection mixed = gf.createGeometryCollection(new Geometry[]{p2d, p3d});
        mixed.setSRID(4326);

        assertThatThrownBy(() ->
                dsl.insertInto(TEST_SPATIAL).set(ID, 99L).set(NAME, "Mixed").set(GEOM_ANY, mixed).execute()
        ).hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining("Mixed-dimension");
    }

    // ==========================================
    // SQL Inline Execution & Multi-Dimension Tests
    // ==========================================

    @Test
    @DisplayName("Real DB [Inline SQL]: 2D (XY) Point insert and query via inlined SQL & static statement")
    void testInlinedSqlXY() {
        Point p2d = gf.createPoint(new Coordinate(116.4074, 39.9042));
        p2d.setSRID(4326);

        // 1. Verify renderInlined SQL string
        org.jooq.InsertSetMoreStep<?> insertQuery = dsl.insertInto(TEST_SPATIAL)
                .set(ID, 201L)
                .set(NAME, "Inline 2D")
                .set(GEOM, p2d);
        String inlinedInsert = dsl.renderInlined(insertQuery);
        assertThat(inlinedInsert).contains("::geometry");

        int rows = dsl.execute(inlinedInsert);
        assertThat(rows).isEqualTo(1);

        // 2. Query back via StatementType.STATIC_STATEMENT (which executes with all params inlined)
        DSLContext staticDsl = DSL.using(connection, SQLDialect.POSTGRES,
                new org.jooq.conf.Settings().withStatementType(org.jooq.conf.StatementType.STATIC_STATEMENT));

        Geometry result = staticDsl.select(GEOM).from(TEST_SPATIAL).where(ID.eq(201L)).fetchOne(GEOM);

        assertThat(result).isNotNull().isInstanceOf(Point.class);
        assertThat(result.getSRID()).isEqualTo(4326);
        assertThat(result.getCoordinate().x).isEqualTo(116.4074);
        assertThat(result.getCoordinate().y).isEqualTo(39.9042);
        assertThat(Double.isNaN(result.getCoordinate().getZ())).isTrue();
    }

    @Test
    @DisplayName("Real DB [Inline SQL]: 3D (XYZ) Point insert and query via inlined SQL & static statement")
    void testInlinedSqlXYZ() {
        Point p3d = gf.createPoint(new Coordinate(116.4, 39.9, 100.5));
        p3d.setSRID(3857);

        DSLContext staticDsl = DSL.using(connection, SQLDialect.POSTGRES,
                new org.jooq.conf.Settings().withStatementType(org.jooq.conf.StatementType.STATIC_STATEMENT));

        staticDsl.insertInto(TEST_SPATIAL)
                .set(ID, 202L)
                .set(NAME, "Inline 3D")
                .set(GEOM_3D, p3d)
                .execute();

        Geometry result = staticDsl.select(GEOM_3D).from(TEST_SPATIAL).where(ID.eq(202L)).fetchOne(GEOM_3D);

        assertThat(result).isNotNull().isInstanceOf(Point.class);
        assertThat(result.getSRID()).isEqualTo(3857);
        assertThat(result.getCoordinate().getZ()).isEqualTo(100.5);
    }

    @Test
    @DisplayName("Real DB [Inline SQL]: 3DM (XYM) Point via inlined EWKT SQL text & static statement")
    void testInlinedSqlXYM() {
        Point pM = gf.createPoint(new CoordinateXYM(116.4, 39.9, 1695888000.0));
        pM.setSRID(4326);

        // Verify M geometry inlines as EWKB hex with the M flag (since 1.0.5)
        org.jooq.InsertSetMoreStep<?> insertQuery = dsl.insertInto(TEST_SPATIAL)
                .set(ID, 203L)
                .set(NAME, "Inline XYM")
                .set(GEOM_M, pM);
        String expectedHex = PostgisCodec.toSpatialRepresentation(pM);
        String inlinedInsert = dsl.renderInlined(insertQuery);
        assertThat(inlinedInsert).contains("'" + expectedHex + "'::geometry");

        DSLContext staticDsl = DSL.using(connection, SQLDialect.POSTGRES,
                new org.jooq.conf.Settings().withStatementType(org.jooq.conf.StatementType.STATIC_STATEMENT));

        staticDsl.insertInto(TEST_SPATIAL)
                .set(ID, 203L)
                .set(NAME, "Inline XYM")
                .set(GEOM_M, pM)
                .execute();

        Geometry result = staticDsl.select(GEOM_M).from(TEST_SPATIAL).where(ID.eq(203L)).fetchOne(GEOM_M);

        assertThat(result).isNotNull().isInstanceOf(Point.class);
        assertThat(result.getSRID()).isEqualTo(4326);
        assertThat(Double.isNaN(result.getCoordinate().getZ())).isTrue();
        assertThat(result.getCoordinate().getM()).isEqualTo(1695888000.0);
    }

    @Test
    @DisplayName("Real DB [Inline SQL]: 4D (XYZM) Polygon via inlined EWKT SQL text & static statement")
    void testInlinedSqlXYZM() {
        CoordinateXYZM[] ring = new CoordinateXYZM[]{
                new CoordinateXYZM(0, 0, 10, 1), new CoordinateXYZM(0, 10, 10, 2),
                new CoordinateXYZM(10, 10, 10, 3), new CoordinateXYZM(10, 0, 10, 4),
                new CoordinateXYZM(0, 0, 10, 1)
        };
        Polygon poly4D = gf.createPolygon(new CoordinateArraySequence(ring));
        poly4D.setSRID(4326);

        org.jooq.InsertSetMoreStep<?> insertQuery = dsl.insertInto(TEST_SPATIAL)
                .set(ID, 204L)
                .set(NAME, "Inline XYZM")
                .set(GEOM_4D, poly4D);
        String expectedPolyHex = PostgisCodec.toSpatialRepresentation(poly4D);
        String inlinedInsert = dsl.renderInlined(insertQuery);
        assertThat(inlinedInsert).contains("'" + expectedPolyHex + "'::geometry");

        DSLContext staticDsl = DSL.using(connection, SQLDialect.POSTGRES,
                new org.jooq.conf.Settings().withStatementType(org.jooq.conf.StatementType.STATIC_STATEMENT));

        staticDsl.insertInto(TEST_SPATIAL)
                .set(ID, 204L)
                .set(NAME, "Inline XYZM")
                .set(GEOM_4D, poly4D)
                .execute();

        Geometry result = staticDsl.select(GEOM_4D).from(TEST_SPATIAL).where(ID.eq(204L)).fetchOne(GEOM_4D);

        assertThat(result).isNotNull().isInstanceOf(Polygon.class);
        assertThat(result.getSRID()).isEqualTo(4326);
        Coordinate c0 = result.getCoordinates()[0];
        assertThat(c0.getZ()).isEqualTo(10.0);
        assertThat(c0.getM()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Real DB [Inline SQL]: NULL geometry and geography inlined as NULL::geometry / NULL::geography")
    void testInlinedSqlNullValues() {
        org.jooq.InsertSetMoreStep<?> insertQuery = dsl.insertInto(TEST_SPATIAL)
                .set(ID, 205L)
                .set(NAME, "Inline NULL")
                .set(GEOM, (Geometry) null)
                .set(GEOG, (Geometry) null);
        String inlinedInsert = dsl.renderInlined(insertQuery);
        assertThat(inlinedInsert).contains("NULL::geometry");
        assertThat(inlinedInsert).contains("NULL::geography");

        DSLContext staticDsl = DSL.using(connection, SQLDialect.POSTGRES,
                new org.jooq.conf.Settings().withStatementType(org.jooq.conf.StatementType.STATIC_STATEMENT));

        staticDsl.execute(inlinedInsert);

        org.jooq.Record record = staticDsl.select(GEOM, GEOG).from(TEST_SPATIAL).where(ID.eq(205L)).fetchOne();
        assertThat(record.get(GEOM)).isNull();
        assertThat(record.get(GEOG)).isNull();
    }

    @Test
    @DisplayName("Real DB [Inline SQL]: Geography spherical distance via inlined query")
    void testInlinedSqlGeographyDistance() {
        Point bj = gf.createPoint(new Coordinate(116.4074, 39.9042));
        bj.setSRID(4326);
        Point sh = gf.createPoint(new Coordinate(121.4737, 31.2304));
        sh.setSRID(4326);

        DSLContext staticDsl = DSL.using(connection, SQLDialect.POSTGRES,
                new org.jooq.conf.Settings().withStatementType(org.jooq.conf.StatementType.STATIC_STATEMENT));

        staticDsl.insertInto(TEST_SPATIAL).set(ID, 210L).set(NAME, "Beijing").set(GEOG, bj).execute();
        staticDsl.insertInto(TEST_SPATIAL).set(ID, 211L).set(NAME, "Shanghai").set(GEOG, sh).execute();

        Double distanceMeters = staticDsl.select(org.jooq.impl.DSL.field("ST_Distance(a.geog, b.geog)", Double.class))
                .from(TEST_SPATIAL.as("a"))
                .crossJoin(TEST_SPATIAL.as("b"))
                .where(org.jooq.impl.DSL.field("a.id", Long.class).eq(210L))
                .and(org.jooq.impl.DSL.field("b.id", Long.class).eq(211L))
                .fetchOne(0, Double.class);

        assertThat(distanceMeters).isNotNull();
        // Distance between Beijing and Shanghai ~1068 km = ~1,068,000 meters
        assertThat(distanceMeters).isBetween(1_050_000.0, 1_100_000.0);
    }

    // ==========================================
    // Update / Geography Z·M / NaN / Structural Integration Tests
    // ==========================================

    @Test
    @DisplayName("Real DB: UPDATE statement round-trips replaced geometries (2D and XYZM columns)")
    void testUpdateGeometry() {
        Point original = gf.createPoint(new Coordinate(116.0, 39.0));
        original.setSRID(4326);
        dsl.insertInto(TEST_SPATIAL).set(ID, 300L).set(NAME, "Before").set(GEOM, original).execute();

        Point updated = gf.createPoint(new Coordinate(121.47, 31.23));
        updated.setSRID(4326);
        int rows = dsl.update(TEST_SPATIAL).set(GEOM, updated).set(NAME, "After").where(ID.eq(300L)).execute();
        assertThat(rows).isEqualTo(1);

        Geometry result = dsl.select(GEOM).from(TEST_SPATIAL).where(ID.eq(300L)).fetchOne(GEOM);
        assertThat(result).isNotNull();
        assertThat(result.getCoordinate().x).isEqualTo(121.47);
        assertThat(result.getSRID()).isEqualTo(4326);

        // XYZM update on the typmod-matched column
        Point updated4D = gf.createPoint(new CoordinateXYZM(121.47, 31.23, 100.0, 1695888000.0));
        updated4D.setSRID(4326);
        dsl.update(TEST_SPATIAL).set(GEOM_4D, updated4D).where(ID.eq(300L)).execute();
        Geometry result4D = dsl.select(GEOM_4D).from(TEST_SPATIAL).where(ID.eq(300L)).fetchOne(GEOM_4D);
        assertThat(result4D).isNotNull();
        assertThat(result4D.getCoordinate().getZ()).isEqualTo(100.0);
        assertThat(result4D.getCoordinate().getM()).isEqualTo(1695888000.0);
    }

    @Test
    @DisplayName("Real DB: GEOGRAPHY(PointZ) column round-trips a Z point")
    void testGeographyZRoundTrip() {
        Point p = gf.createPoint(new Coordinate(121.4737, 31.2304, 50.0));
        p.setSRID(4326);
        dsl.insertInto(TEST_SPATIAL).set(ID, 301L).set(NAME, "Geog Z").set(GEOG_Z, p).execute();

        Geometry result = dsl.select(GEOG_Z).from(TEST_SPATIAL).where(ID.eq(301L)).fetchOne(GEOG_Z);
        assertThat(result).isNotNull();
        assertThat(result.getSRID()).isEqualTo(4326);
        assertThat(result.getCoordinate().getZ()).isEqualTo(50.0);
        assertThat(Double.isNaN(result.getCoordinate().getM())).isTrue();
    }

    @Test
    @DisplayName("Real DB: GEOGRAPHY(PointM) column round-trips an M point")
    void testGeographyMRoundTrip() {
        Point p = gf.createPoint(new CoordinateXYM(121.4737, 31.2304, 1695888000.0));
        p.setSRID(4326);
        dsl.insertInto(TEST_SPATIAL).set(ID, 302L).set(NAME, "Geog M").set(GEOG_M, p).execute();

        Geometry result = dsl.select(GEOG_M).from(TEST_SPATIAL).where(ID.eq(302L)).fetchOne(GEOG_M);
        assertThat(result).isNotNull();
        Coordinate c = result.getCoordinate();
        assertThat(Double.isNaN(c.getZ())).isTrue();
        assertThat(c.getM()).isEqualTo(1695888000.0);
    }

    @Test
    @DisplayName("Real DB: LINESTRING Z with NaN Z (legal PostGIS data) inserts and reads back without error")
    void testNanZLinestringRoundTrip() {
        LineString ls = gf.createLineString(new Coordinate[]{
                new Coordinate(0, 0, Double.NaN), new Coordinate(1, 1, 5)
        });
        ls.setSRID(3857);
        dsl.insertInto(TEST_SPATIAL).set(ID, 303L).set(NAME, "NaN Z").set(GEOM_3D, ls).execute();

        Geometry result = dsl.select(GEOM_3D).from(TEST_SPATIAL).where(ID.eq(303L)).fetchOne(GEOM_3D);
        assertThat(result).isNotNull().isInstanceOf(LineString.class);
        Coordinate[] coords = result.getCoordinates();
        assertThat(Double.isNaN(coords[0].getZ())).isTrue();
        assertThat(coords[1].getZ()).isEqualTo(5.0);
        assertThat(result.getSRID()).isEqualTo(3857);
    }

    @Test
    @DisplayName("Real DB: GeometryCollection M containing an EMPTY component round-trips")
    void testGeometryCollectionMWithEmptyComponentDb() {
        Point empty = gf.createPoint();
        Point p = gf.createPoint(new CoordinateXYM(1, 2, 10));
        GeometryCollection gc = gf.createGeometryCollection(new Geometry[]{empty, p});
        gc.setSRID(4326);

        dsl.insertInto(TEST_SPATIAL).set(ID, 304L).set(NAME, "GC M empty child").set(GEOM_ANY, gc).execute();

        Geometry result = dsl.select(GEOM_ANY).from(TEST_SPATIAL).where(ID.eq(304L)).fetchOne(GEOM_ANY);
        assertThat(result).isInstanceOf(GeometryCollection.class);
        GeometryCollection rgc = (GeometryCollection) result;
        assertThat(rgc.getNumGeometries()).isEqualTo(2);
        assertThat(rgc.getGeometryN(0).isEmpty()).isTrue();
        assertThat(rgc.getGeometryN(1).getCoordinate().getM()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("Real DB: JTS EMPTY into a Z-typmod column is rejected; explicit-dimension empty succeeds")
    void testEmptyGeometryIntoDimensionedTypmodColumn() {
        // JTS cannot represent "POINT Z EMPTY": an empty geometry serializes as 2D and
        // PostgreSQL rejects it for a GEOMETRY(GeometryZ) column.
        Point empty = gf.createPoint();
        empty.setSRID(3857);
        assertThatThrownBy(() ->
                dsl.insertInto(TEST_SPATIAL).set(ID, 306L).set(NAME, "Empty into typmod").set(GEOM_3D, empty).execute()
        ).isInstanceOf(DataAccessException.class);

        // Workaround when the column type is known: declare the dimension explicitly.
        // The hex literal only ever contains [0-9a-f], so inline concatenation is injection-safe.
        String hex = PostgisCodec.toSpatialRepresentation(empty, DimensionAnalyzer.CoordinateDimension.XYZ);
        assertThat(hex).startsWith("00A0");
        dsl.execute("INSERT INTO test_spatial (id, name, geom_3d) VALUES (306, 'Explicit-dim empty', '" + hex + "'::geometry)");

        Geometry back = dsl.select(GEOM_3D).from(TEST_SPATIAL).where(ID.eq(306L)).fetchOne(GEOM_3D);
        assertThat(back).isNotNull();
        assertThat(back.isEmpty()).isTrue();
        assertThat(back.getSRID()).isEqualTo(3857);

        Integer ndims = dsl.select(DSL.field("ST_NDims(geom_3d)", Integer.class))
                .from(TEST_SPATIAL).where(ID.eq(306L))
                .fetchOne(0, Integer.class);
        assertThat(ndims).isEqualTo(3);
    }

    @Test
    @DisplayName("Real DB: 50,000-vertex LineString round-trips exactly")
    void testLargeLinestringRoundTrip() {
        int vertexCount = 50_000;
        Coordinate[] coords = new Coordinate[vertexCount];
        for (int i = 0; i < vertexCount; i++) {
            coords[i] = new Coordinate(i * 0.0001, (i % 100) * 0.0001);
        }
        LineString line = gf.createLineString(coords);
        line.setSRID(4326);

        dsl.insertInto(TEST_SPATIAL).set(ID, 305L).set(NAME, "Large line").set(GEOM, line).execute();

        Geometry result = dsl.select(GEOM).from(TEST_SPATIAL).where(ID.eq(305L)).fetchOne(GEOM);
        assertThat(result).isInstanceOf(LineString.class);
        assertThat(result.getCoordinates()).hasSize(vertexCount);
        assertThat(result.equalsExact(line)).isTrue();
    }
}
