package top.yunitytech.maven.jooq.binding;

import org.jooq.*;
import org.jooq.conf.ParamType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.postgresql.util.PGobject;

import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgisGeometryBindingTest {

    private final GeometryFactory gf = AbstractPostgisBinding.GEOMETRY_FACTORY;
    private PostgisGeometryBinding binding;
    private PostgisGeographyBinding geographyBinding;
    private Converter<Object, Geometry> converter;

    @BeforeEach
    void setUp() {
        binding = new PostgisGeometryBinding();
        geographyBinding = new PostgisGeographyBinding();
        converter = binding.converter();
    }

    @Nested
    @DisplayName("GeometryConverter Basic Format Tests")
    class ConverterTests {

        @Test
        @DisplayName("null input returns null for both directions")
        void testNullHandling() {
            assertThat(converter.from(null)).isNull();
            assertThat(converter.to(null)).isNull();
        }

        @Test
        @DisplayName("Converter accepts PGobject with type 'geometry'")
        void testPGobjectGeometry() throws SQLException {
            Point point = gf.createPoint(new Coordinate(1.0, 2.0));
            point.setSRID(4326);
            String hex = (String) converter.to(point);

            PGobject pgObject = new PGobject();
            pgObject.setType("geometry");
            pgObject.setValue(hex);

            Geometry result = converter.from(pgObject);
            assertThat(result).isNotNull();
            assertThat(result.getSRID()).isEqualTo(4326);
            assertThat(result.getCoordinate().x).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Converter accepts PGobject with type 'geography'")
        void testPGobjectGeography() throws SQLException {
            Point point = gf.createPoint(new Coordinate(1.0, 2.0));
            point.setSRID(4326);
            String hex = (String) converter.to(point);

            PGobject pgObject = new PGobject();
            pgObject.setType("geography");
            pgObject.setValue(hex);

            Geometry result = converter.from(pgObject);
            assertThat(result).isNotNull();
            assertThat(result.getSRID()).isEqualTo(4326);
        }

        @Test
        @DisplayName("Converter accepts PGobject with WKT text format")
        void testPGobjectWithWktText() throws SQLException {
            PGobject pgObject = new PGobject();
            pgObject.setType("geometry");
            pgObject.setValue("POINT(10 20)");

            Geometry result = converter.from(pgObject);
            assertThat(result).isNotNull();
            assertThat(result.getCoordinate().x).isEqualTo(10.0);
            assertThat(result.getCoordinate().y).isEqualTo(20.0);
        }

        @Test
        @DisplayName("Converter accepts PGobject with EWKT text format")
        void testPGobjectWithEwktText() throws SQLException {
            PGobject pgObject = new PGobject();
            pgObject.setType("geometry");
            pgObject.setValue("SRID=3857;POINT(100 200)");

            Geometry result = converter.from(pgObject);
            assertThat(result).isNotNull();
            assertThat(result.getSRID()).isEqualTo(3857);
            assertThat(result.getCoordinate().x).isEqualTo(100.0);
            assertThat(result.getCoordinate().y).isEqualTo(200.0);
        }

        @Test
        @DisplayName("Converter handles PGobject with null value")
        void testPGobjectNullValue() throws SQLException {
            PGobject pgObject = new PGobject();
            pgObject.setType("geometry");
            pgObject.setValue(null);

            Geometry result = converter.from(pgObject);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Converter accepts raw binary byte[] WKB")
        void testByteArraySupport() {
            Point point = gf.createPoint(new Coordinate(1.0, 2.0));
            point.setSRID(4326);
            byte[] bytes = new WKBWriter(2, true).write(point);

            Geometry result = converter.from(bytes);
            assertThat(result).isNotNull();
            assertThat(result.getSRID()).isEqualTo(4326);
        }

        @Test
        @DisplayName("Converter types are Object and Geometry")
        void testConverterTypes() {
            assertThat(converter.fromType()).isEqualTo(Object.class);
            assertThat(converter.toType()).isEqualTo(Geometry.class);
        }
    }

    @Nested
    @DisplayName("Dimension Matrix Round-Trip Tests (XY, XYZ, XYM, XYZM across SRID 4326, 3857, 4490, 0)")
    class DimensionMatrixTests {

        // --- 1. POINT ---

        @ParameterizedTest(name = "Point 2D (XY) round-trip with SRID {0}")
        @ValueSource(ints = {4326, 3857, 4490, 0})
        void testPoint2D(int srid) {
            Point p = gf.createPoint(new Coordinate(116.4, 39.9));
            p.setSRID(srid);

            Object repr = converter.to(p);
            Geometry restored = converter.from(repr);

            assertThat(restored).isInstanceOf(Point.class);
            assertThat(restored.getSRID()).isEqualTo(srid);
            Coordinate c = restored.getCoordinate();
            assertThat(c.x).isEqualTo(116.4);
            assertThat(c.y).isEqualTo(39.9);
            assertThat(Double.isNaN(c.getZ())).isTrue();
            assertThat(Double.isNaN(c.getM())).isTrue();
        }

        @ParameterizedTest(name = "Point 3D (XYZ) round-trip with SRID {0}")
        @ValueSource(ints = {4326, 3857, 4490, 0})
        void testPoint3D(int srid) {
            Point p = gf.createPoint(new Coordinate(116.4, 39.9, 100.0));
            p.setSRID(srid);

            Object repr = converter.to(p);
            Geometry restored = converter.from(repr);

            assertThat(restored).isInstanceOf(Point.class);
            assertThat(restored.getSRID()).isEqualTo(srid);
            Coordinate c = restored.getCoordinate();
            assertThat(c.x).isEqualTo(116.4);
            assertThat(c.y).isEqualTo(39.9);
            assertThat(c.getZ()).isEqualTo(100.0);
            assertThat(Double.isNaN(c.getM())).isTrue();
        }

        @ParameterizedTest(name = "Point 3DM (XYM) round-trip with SRID {0}")
        @ValueSource(ints = {4326, 3857, 4490, 0})
        void testPointXYM(int srid) {
            Point p = gf.createPoint(new CoordinateXYM(116.4, 39.9, 50.0));
            p.setSRID(srid);

            Object repr = converter.to(p);
            Geometry restored = converter.from(repr);

            assertThat(restored).isInstanceOf(Point.class);
            assertThat(restored.getSRID()).isEqualTo(srid);
            Coordinate c = restored.getCoordinate();
            assertThat(c.x).isEqualTo(116.4);
            assertThat(c.y).isEqualTo(39.9);
            assertThat(Double.isNaN(c.getZ())).isTrue();
            assertThat(c.getM()).isEqualTo(50.0);
        }

        @ParameterizedTest(name = "Point 4D (XYZM) round-trip with SRID {0}")
        @ValueSource(ints = {4326, 3857, 4490, 0})
        void testPointXYZM(int srid) {
            Point p = gf.createPoint(new CoordinateXYZM(116.4, 39.9, 100.0, 50.0));
            p.setSRID(srid);

            Object repr = converter.to(p);
            Geometry restored = converter.from(repr);

            assertThat(restored).isInstanceOf(Point.class);
            assertThat(restored.getSRID()).isEqualTo(srid);
            Coordinate c = restored.getCoordinate();
            assertThat(c.x).isEqualTo(116.4);
            assertThat(c.y).isEqualTo(39.9);
            assertThat(c.getZ()).isEqualTo(100.0);
            assertThat(c.getM()).isEqualTo(50.0);
        }

        // --- 2. POLYGON WITH HOLE ---

        @ParameterizedTest(name = "Polygon 2D with hole round-trip with SRID {0}")
        @ValueSource(ints = {4326, 3857, 4490})
        void testPolygon2DWithHole(int srid) {
            Coordinate[] shell = new Coordinate[]{
                    new Coordinate(0, 0), new Coordinate(0, 20),
                    new Coordinate(20, 20), new Coordinate(20, 0), new Coordinate(0, 0)
            };
            Coordinate[] hole = new Coordinate[]{
                    new Coordinate(5, 5), new Coordinate(5, 10),
                    new Coordinate(10, 10), new Coordinate(10, 5), new Coordinate(5, 5)
            };
            Polygon poly = gf.createPolygon(gf.createLinearRing(shell), new LinearRing[]{gf.createLinearRing(hole)});
            poly.setSRID(srid);

            Object repr = converter.to(poly);
            Geometry restored = converter.from(repr);

            assertThat(restored).isInstanceOf(Polygon.class);
            assertThat(restored.getSRID()).isEqualTo(srid);
            Polygon rp = (Polygon) restored;
            assertThat(rp.getNumInteriorRing()).isEqualTo(1);
            assertThat(Double.isNaN(rp.getCoordinate().getZ())).isTrue();
            assertThat(Double.isNaN(rp.getCoordinate().getM())).isTrue();
        }

        @ParameterizedTest(name = "Polygon 3D (XYZ) with hole round-trip with SRID {0}")
        @ValueSource(ints = {4326, 3857, 4490})
        void testPolygon3DWithHole(int srid) {
            Coordinate[] shell = new Coordinate[]{
                    new Coordinate(0, 0, 10), new Coordinate(0, 20, 10),
                    new Coordinate(20, 20, 10), new Coordinate(20, 0, 10), new Coordinate(0, 0, 10)
            };
            Coordinate[] hole = new Coordinate[]{
                    new Coordinate(5, 5, 12), new Coordinate(5, 10, 12),
                    new Coordinate(10, 10, 12), new Coordinate(10, 5, 12), new Coordinate(5, 5, 12)
            };
            Polygon poly = gf.createPolygon(gf.createLinearRing(shell), new LinearRing[]{gf.createLinearRing(hole)});
            poly.setSRID(srid);

            Object repr = converter.to(poly);
            Geometry restored = converter.from(repr);

            assertThat(restored).isInstanceOf(Polygon.class);
            assertThat(restored.getSRID()).isEqualTo(srid);
            Polygon rp = (Polygon) restored;
            assertThat(rp.getNumInteriorRing()).isEqualTo(1);
            assertThat(rp.getExteriorRing().getCoordinateN(0).getZ()).isEqualTo(10.0);
            assertThat(rp.getInteriorRingN(0).getCoordinateN(0).getZ()).isEqualTo(12.0);
            assertThat(Double.isNaN(rp.getCoordinate().getM())).isTrue();
        }

        @ParameterizedTest(name = "Polygon 3DM (XYM) with hole round-trip with SRID {0}")
        @ValueSource(ints = {4326, 3857, 4490})
        void testPolygonXYMWithHole(int srid) {
            CoordinateXYM[] shell = new CoordinateXYM[]{
                    new CoordinateXYM(0, 0, 1), new CoordinateXYM(0, 20, 2),
                    new CoordinateXYM(20, 20, 3), new CoordinateXYM(20, 0, 4), new CoordinateXYM(0, 0, 1)
            };
            CoordinateXYM[] hole = new CoordinateXYM[]{
                    new CoordinateXYM(5, 5, 5), new CoordinateXYM(5, 10, 6),
                    new CoordinateXYM(10, 10, 7), new CoordinateXYM(10, 5, 8), new CoordinateXYM(5, 5, 5)
            };
            LinearRing sRing = gf.createLinearRing(new CoordinateArraySequence(shell));
            LinearRing hRing = gf.createLinearRing(new CoordinateArraySequence(hole));
            Polygon poly = gf.createPolygon(sRing, new LinearRing[]{hRing});
            poly.setSRID(srid);

            Object repr = converter.to(poly);
            Geometry restored = converter.from(repr);

            assertThat(restored).isInstanceOf(Polygon.class);
            assertThat(restored.getSRID()).isEqualTo(srid);
            Polygon rp = (Polygon) restored;
            assertThat(rp.getNumInteriorRing()).isEqualTo(1);
            Coordinate sc = rp.getExteriorRing().getCoordinateN(0);
            assertThat(Double.isNaN(sc.getZ())).isTrue();
            assertThat(sc.getM()).isEqualTo(1.0);
            Coordinate hc = rp.getInteriorRingN(0).getCoordinateN(0);
            assertThat(Double.isNaN(hc.getZ())).isTrue();
            assertThat(hc.getM()).isEqualTo(5.0);
        }

        @ParameterizedTest(name = "Polygon 4D (XYZM) with hole round-trip with SRID {0}")
        @ValueSource(ints = {4326, 3857, 4490})
        void testPolygonXYZMWithHole(int srid) {
            CoordinateXYZM[] shell = new CoordinateXYZM[]{
                    new CoordinateXYZM(0, 0, 10, 1), new CoordinateXYZM(0, 20, 10, 2),
                    new CoordinateXYZM(20, 20, 10, 3), new CoordinateXYZM(20, 0, 10, 4), new CoordinateXYZM(0, 0, 10, 1)
            };
            CoordinateXYZM[] hole = new CoordinateXYZM[]{
                    new CoordinateXYZM(5, 5, 15, 5), new CoordinateXYZM(5, 10, 15, 6),
                    new CoordinateXYZM(10, 10, 15, 7), new CoordinateXYZM(10, 5, 15, 8), new CoordinateXYZM(5, 5, 15, 5)
            };
            LinearRing sRing = gf.createLinearRing(new CoordinateArraySequence(shell));
            LinearRing hRing = gf.createLinearRing(new CoordinateArraySequence(hole));
            Polygon poly = gf.createPolygon(sRing, new LinearRing[]{hRing});
            poly.setSRID(srid);

            Object repr = converter.to(poly);
            Geometry restored = converter.from(repr);

            assertThat(restored).isInstanceOf(Polygon.class);
            assertThat(restored.getSRID()).isEqualTo(srid);
            Polygon rp = (Polygon) restored;
            assertThat(rp.getNumInteriorRing()).isEqualTo(1);
            Coordinate sc = rp.getExteriorRing().getCoordinateN(0);
            assertThat(sc.getZ()).isEqualTo(10.0);
            assertThat(sc.getM()).isEqualTo(1.0);
            Coordinate hc = rp.getInteriorRingN(0).getCoordinateN(0);
            assertThat(hc.getZ()).isEqualTo(15.0);
            assertThat(hc.getM()).isEqualTo(5.0);
        }

        // --- 3. MULTIPOLYGON ---

        @ParameterizedTest(name = "MultiPolygon 4D (XYZM) round-trip with SRID {0}")
        @ValueSource(ints = {4326, 3857, 4490})
        void testMultiPolygonXYZM(int srid) {
            CoordinateXYZM[] p1Coords = new CoordinateXYZM[]{
                    new CoordinateXYZM(0, 0, 1, 1), new CoordinateXYZM(0, 5, 1, 2),
                    new CoordinateXYZM(5, 5, 1, 3), new CoordinateXYZM(5, 0, 1, 4), new CoordinateXYZM(0, 0, 1, 1)
            };
            CoordinateXYZM[] p2Coords = new CoordinateXYZM[]{
                    new CoordinateXYZM(10, 10, 2, 5), new CoordinateXYZM(10, 15, 2, 6),
                    new CoordinateXYZM(15, 15, 2, 7), new CoordinateXYZM(15, 10, 2, 8), new CoordinateXYZM(10, 10, 2, 5)
            };
            Polygon p1 = gf.createPolygon(new CoordinateArraySequence(p1Coords));
            Polygon p2 = gf.createPolygon(new CoordinateArraySequence(p2Coords));
            MultiPolygon mp = gf.createMultiPolygon(new Polygon[]{p1, p2});
            mp.setSRID(srid);

            Object repr = converter.to(mp);
            Geometry restored = converter.from(repr);

            assertThat(restored).isInstanceOf(MultiPolygon.class);
            assertThat(restored.getSRID()).isEqualTo(srid);
            MultiPolygon rmp = (MultiPolygon) restored;
            assertThat(rmp.getNumGeometries()).isEqualTo(2);
            assertThat(rmp.getGeometryN(0).getCoordinate().getZ()).isEqualTo(1.0);
            assertThat(rmp.getGeometryN(0).getCoordinate().getM()).isEqualTo(1.0);
            assertThat(rmp.getGeometryN(1).getCoordinate().getZ()).isEqualTo(2.0);
            assertThat(rmp.getGeometryN(1).getCoordinate().getM()).isEqualTo(5.0);
        }

        // --- 4. GEOMETRYCOLLECTION ---

        @ParameterizedTest(name = "GeometryCollection 3DM (XYM) round-trip with SRID {0}")
        @ValueSource(ints = {4326, 3857, 4490})
        void testGeometryCollectionXYM(int srid) {
            Point p = gf.createPoint(new CoordinateXYM(10, 20, 30));
            CoordinateXYM[] lineCoords = new CoordinateXYM[]{
                    new CoordinateXYM(0, 0, 1), new CoordinateXYM(5, 5, 2)
            };
            LineString ls = gf.createLineString(new CoordinateArraySequence(lineCoords));
            GeometryCollection gc = gf.createGeometryCollection(new Geometry[]{p, ls});
            gc.setSRID(srid);

            Object repr = converter.to(gc);
            Geometry restored = converter.from(repr);

            assertThat(restored).isInstanceOf(GeometryCollection.class);
            assertThat(restored.getSRID()).isEqualTo(srid);
            GeometryCollection rgc = (GeometryCollection) restored;
            assertThat(rgc.getNumGeometries()).isEqualTo(2);

            Coordinate pc = rgc.getGeometryN(0).getCoordinate();
            assertThat(Double.isNaN(pc.getZ())).isTrue();
            assertThat(pc.getM()).isEqualTo(30.0);

            Coordinate lc = rgc.getGeometryN(1).getCoordinate();
            assertThat(Double.isNaN(lc.getZ())).isTrue();
            assertThat(lc.getM()).isEqualTo(1.0);
        }

        @ParameterizedTest(name = "GeometryCollection 4D (XYZM) round-trip with SRID {0}")
        @ValueSource(ints = {4326, 3857, 4490})
        void testGeometryCollectionXYZM(int srid) {
            Point p = gf.createPoint(new CoordinateXYZM(10, 20, 100, 30));
            CoordinateXYZM[] lineCoords = new CoordinateXYZM[]{
                    new CoordinateXYZM(0, 0, 10, 1), new CoordinateXYZM(5, 5, 20, 2)
            };
            LineString ls = gf.createLineString(new CoordinateArraySequence(lineCoords));
            GeometryCollection gc = gf.createGeometryCollection(new Geometry[]{p, ls});
            gc.setSRID(srid);

            Object repr = converter.to(gc);
            Geometry restored = converter.from(repr);

            assertThat(restored).isInstanceOf(GeometryCollection.class);
            assertThat(restored.getSRID()).isEqualTo(srid);
            GeometryCollection rgc = (GeometryCollection) restored;
            assertThat(rgc.getNumGeometries()).isEqualTo(2);

            Coordinate pc = rgc.getGeometryN(0).getCoordinate();
            assertThat(pc.getZ()).isEqualTo(100.0);
            assertThat(pc.getM()).isEqualTo(30.0);

            Coordinate lc = rgc.getGeometryN(1).getCoordinate();
            assertThat(lc.getZ()).isEqualTo(10.0);
            assertThat(lc.getM()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("EMPTY Geometry Round-Trip Tests (SRID 4326, 3857, 4490)")
    class EmptyGeometryTests {

        @ParameterizedTest(name = "Point EMPTY round-trip with SRID {0}")
        @ValueSource(ints = {4326, 3857, 4490})
        void testPointEmpty(int srid) {
            Point p = gf.createPoint();
            p.setSRID(srid);

            Object repr = converter.to(p);
            Geometry restored = converter.from(repr);

            assertThat(restored).isInstanceOf(Point.class);
            assertThat(restored.isEmpty()).isTrue();
            assertThat(restored.getSRID()).isEqualTo(srid);
        }

        @ParameterizedTest(name = "Polygon EMPTY round-trip with SRID {0}")
        @ValueSource(ints = {4326, 3857, 4490})
        void testPolygonEmpty(int srid) {
            Polygon poly = gf.createPolygon();
            poly.setSRID(srid);

            Object repr = converter.to(poly);
            Geometry restored = converter.from(repr);

            assertThat(restored).isInstanceOf(Polygon.class);
            assertThat(restored.isEmpty()).isTrue();
            assertThat(restored.getSRID()).isEqualTo(srid);
        }

        @ParameterizedTest(name = "MultiPolygon EMPTY round-trip with SRID {0}")
        @ValueSource(ints = {4326, 3857, 4490})
        void testMultiPolygonEmpty(int srid) {
            MultiPolygon mp = gf.createMultiPolygon();
            mp.setSRID(srid);

            Object repr = converter.to(mp);
            Geometry restored = converter.from(repr);

            assertThat(restored).isInstanceOf(MultiPolygon.class);
            assertThat(restored.isEmpty()).isTrue();
            assertThat(restored.getSRID()).isEqualTo(srid);
        }

        @ParameterizedTest(name = "GeometryCollection EMPTY round-trip with SRID {0}")
        @ValueSource(ints = {4326, 3857, 4490})
        void testGeometryCollectionEmpty(int srid) {
            GeometryCollection gc = gf.createGeometryCollection();
            gc.setSRID(srid);

            Object repr = converter.to(gc);
            Geometry restored = converter.from(repr);

            assertThat(restored).isInstanceOf(GeometryCollection.class);
            assertThat(restored.isEmpty()).isTrue();
            assertThat(restored.getSRID()).isEqualTo(srid);
        }

        @Test
        @DisplayName("GeometryCollection with empty component does not drop empty component")
        void testGeometryCollectionWithEmptyComponent() {
            Point empty = gf.createPoint();
            Point p = gf.createPoint(new Coordinate(1, 2));
            GeometryCollection gc = gf.createGeometryCollection(new Geometry[]{empty, p});
            gc.setSRID(4326);

            Object repr = converter.to(gc);
            Geometry restored = converter.from(repr);

            assertThat(restored).isInstanceOf(GeometryCollection.class);
            GeometryCollection rgc = (GeometryCollection) restored;
            assertThat(rgc.getNumGeometries()).isEqualTo(2);
            assertThat(rgc.getGeometryN(0).isEmpty()).isTrue();
            assertThat(rgc.getGeometryN(1).isEmpty()).isFalse();
            assertThat(rgc.getGeometryN(1).getCoordinate().x).isEqualTo(1.0);
        }

        @Test
        @DisplayName("GeometryCollection XYM with empty component preserves structure and M")
        void testGeometryCollectionXYMWithEmptyComponent() {
            Point empty = gf.createPoint();
            Point p = gf.createPoint(new CoordinateXYM(1, 2, 10));
            GeometryCollection gc = gf.createGeometryCollection(new Geometry[]{empty, p});
            gc.setSRID(4326);

            Object repr = converter.to(gc);
            Geometry restored = converter.from(repr);

            assertThat(restored).isInstanceOf(GeometryCollection.class);
            GeometryCollection rgc = (GeometryCollection) restored;
            assertThat(rgc.getNumGeometries()).isEqualTo(2);
            assertThat(rgc.getGeometryN(0).isEmpty()).isTrue();
            assertThat(rgc.getGeometryN(1).getCoordinate().getM()).isEqualTo(10.0);
            assertThat(Double.isNaN(rgc.getGeometryN(1).getCoordinate().getZ())).isTrue();
        }
    }

    @Nested
    @DisplayName("WKTWriter(4) XYM Output & WKBReader EWKB M/ZM Reading Tests")
    class WktOutputAndWkbReadingTests {

        @Test
        @DisplayName("WKTWriter(4) outputs POINT M, POLYGON M, GEOMETRYCOLLECTION M for XYM")
        void testWktWriter4OutputForXYM() {
            Point p = gf.createPoint(new CoordinateXYM(10, 20, 30));
            p.setSRID(4326);
            String pRepr = (String) converter.to(p);
            assertThat(pRepr).startsWith("SRID=4326;POINT M");

            CoordinateXYM[] shell = new CoordinateXYM[]{
                    new CoordinateXYM(0, 0, 1), new CoordinateXYM(0, 10, 2),
                    new CoordinateXYM(10, 10, 3), new CoordinateXYM(10, 0, 4), new CoordinateXYM(0, 0, 1)
            };
            Polygon poly = gf.createPolygon(new CoordinateArraySequence(shell));
            poly.setSRID(4326);
            String polyRepr = (String) converter.to(poly);
            assertThat(polyRepr).startsWith("SRID=4326;POLYGON M");

            GeometryCollection gc = gf.createGeometryCollection(new Geometry[]{p, poly});
            gc.setSRID(4326);
            String gcRepr = (String) converter.to(gc);
            assertThat(gcRepr).startsWith("SRID=4326;GEOMETRYCOLLECTION M");
        }

        @Test
        @DisplayName("WKBReader / parseWkb correctly decodes EWKB Hex with M flag (bit 0x40000000) to CoordinateXYM")
        void testParseHexEWKBWithM() {
            // PostGIS little-endian EWKB for: SRID=4326;POINT M(116.4 39.9 50.0)
            // type = 0x60000001 (SRID | M | Point)
            String hexM = "0101000060E61000009A99999999195D403333333333F343400000000000004940";

            Geometry geom = converter.from(hexM);

            assertThat(geom).isInstanceOf(Point.class);
            assertThat(geom.getSRID()).isEqualTo(4326);
            Coordinate c = geom.getCoordinate();
            assertThat(c).isInstanceOf(CoordinateXYM.class);
            assertThat(c.x).isEqualTo(116.4);
            assertThat(c.y).isEqualTo(39.9);
            assertThat(Double.isNaN(c.getZ())).isTrue();
            assertThat(c.getM()).isEqualTo(50.0);
        }

        @Test
        @DisplayName("WKBReader / parseWkb correctly decodes EWKB Hex with ZM flags (0x80000000 | 0x40000000) to CoordinateXYZM")
        void testParseHexEWKBWithZM() {
            // PostGIS little-endian EWKB for: SRID=4326;POINT ZM(116.4 39.9 100.0 50.0)
            // type = 0xE0000001 (SRID | Z | M | Point)
            String hexZM = "01010000E0E61000009A99999999195D403333333333F3434000000000000059400000000000004940";

            Geometry geom = converter.from(hexZM);

            assertThat(geom).isInstanceOf(Point.class);
            assertThat(geom.getSRID()).isEqualTo(4326);
            Coordinate c = geom.getCoordinate();
            assertThat(c).isInstanceOf(CoordinateXYZM.class);
            assertThat(c.x).isEqualTo(116.4);
            assertThat(c.y).isEqualTo(39.9);
            assertThat(c.getZ()).isEqualTo(100.0);
            assertThat(c.getM()).isEqualTo(50.0);
        }

        @Test
        @DisplayName("WKBReader / parseWkb correctly decodes binary byte[] EWKB with M flag")
        void testParseBinaryEWKBWithM() {
            String hexM = "0101000060E61000009A99999999195D403333333333F343400000000000004940";
            byte[] bytes = WKBReader.hexToBytes(hexM);

            Geometry geom = converter.from(bytes);

            assertThat(geom).isInstanceOf(Point.class);
            assertThat(geom.getCoordinate()).isInstanceOf(CoordinateXYM.class);
            assertThat(geom.getCoordinate().getM()).isEqualTo(50.0);
            assertThat(Double.isNaN(geom.getCoordinate().getZ())).isTrue();
        }

        @Test
        @DisplayName("WKBReader / parseWkb correctly decodes binary byte[] EWKB with ZM flag")
        void testParseBinaryEWKBWithZM() {
            String hexZM = "01010000E0E61000009A99999999195D403333333333F3434000000000000059400000000000004940";
            byte[] bytes = WKBReader.hexToBytes(hexZM);

            Geometry geom = converter.from(bytes);

            assertThat(geom).isInstanceOf(Point.class);
            assertThat(geom.getCoordinate()).isInstanceOf(CoordinateXYZM.class);
            assertThat(geom.getCoordinate().getZ()).isEqualTo(100.0);
            assertThat(geom.getCoordinate().getM()).isEqualTo(50.0);
        }
    }

    @Nested
    @DisplayName("Mixed-Dimension Geometry Rejection Tests")
    class MixedDimensionRejectionTests {

        @Test
        @DisplayName("Reject GeometryCollection mixing 2D (XY) and 3D (XYZ) in converter.to()")
        void testRejectMixedXYAndXYZInTo() {
            Point p2d = gf.createPoint(new Coordinate(1, 2));
            Point p3d = gf.createPoint(new Coordinate(3, 4, 5));
            GeometryCollection gc = gf.createGeometryCollection(new Geometry[]{p2d, p3d});

            assertThatThrownBy(() -> converter.to(gc))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Mixed-dimension");
        }

        @Test
        @DisplayName("Reject GeometryCollection mixing 2D (XY) and 3DM (XYM) in converter.to()")
        void testRejectMixedXYAndXYMInTo() {
            Point p2d = gf.createPoint(new Coordinate(1, 2));
            Point pxym = gf.createPoint(new CoordinateXYM(3, 4, 5));
            GeometryCollection gc = gf.createGeometryCollection(new Geometry[]{p2d, pxym});

            assertThatThrownBy(() -> converter.to(gc))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Mixed-dimension");
        }

        @Test
        @DisplayName("Reject GeometryCollection mixing 3D (XYZ) and 3DM (XYM) in converter.to()")
        void testRejectMixedXYZAndXYMInTo() {
            Point p3d = gf.createPoint(new Coordinate(1, 2, 3));
            Point pxym = gf.createPoint(new CoordinateXYM(4, 5, 6));
            GeometryCollection gc = gf.createGeometryCollection(new Geometry[]{p3d, pxym});

            assertThatThrownBy(() -> converter.to(gc))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Mixed-dimension");
        }

        @Test
        @DisplayName("Reject GeometryCollection mixing 3D (XYZ) and 4D (XYZM) in converter.to()")
        void testRejectMixedXYZAndXYZMInTo() {
            Point p3d = gf.createPoint(new Coordinate(1, 2, 3));
            Point pxyzm = gf.createPoint(new CoordinateXYZM(4, 5, 6, 7));
            GeometryCollection gc = gf.createGeometryCollection(new Geometry[]{p3d, pxyzm});

            assertThatThrownBy(() -> converter.to(gc))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Mixed-dimension");
        }

        @Test
        @DisplayName("Reject GeometryCollection mixing 2D (XY) and 4D (XYZM) in converter.to()")
        void testRejectMixedXYAndXYZMInTo() {
            Point p2d = gf.createPoint(new Coordinate(1, 2));
            Point pxyzm = gf.createPoint(new CoordinateXYZM(3, 4, 5, 6));
            GeometryCollection gc = gf.createGeometryCollection(new Geometry[]{p2d, pxyzm});

            assertThatThrownBy(() -> converter.to(gc))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Mixed-dimension");
        }

        @Test
        @DisplayName("Reject nested GeometryCollection with mixed dimensions in converter.to()")
        void testRejectNestedMixedGeometryCollection() {
            Point p2d = gf.createPoint(new Coordinate(1, 2));
            Point p3d = gf.createPoint(new Coordinate(3, 4, 5));
            GeometryCollection inner = gf.createGeometryCollection(new Geometry[]{p3d});
            GeometryCollection root = gf.createGeometryCollection(new Geometry[]{p2d, inner});

            assertThatThrownBy(() -> converter.to(root))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Mixed-dimension");
        }

        @Test
        @DisplayName("Reject mixed-dimension EWKT in converter.from()")
        void testRejectMixedEWKTInFrom() {
            String mixedEwkt = "SRID=4326;GEOMETRYCOLLECTION(POINT(1 2), POINT Z(3 4 5))";

            assertThatThrownBy(() -> converter.from(mixedEwkt))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Mixed-dimension");
        }

        @Test
        @DisplayName("Reject mixed-dimension standard WKT in converter.from()")
        void testRejectMixedWktInFrom() {
            String mixedWkt = "GEOMETRYCOLLECTION(POINT(1 2), POINT Z(3 4 5))";

            assertThatThrownBy(() -> converter.from(mixedWkt))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Mixed-dimension");
        }

        @Test
        @DisplayName("Reject mixed-dimension EWKB binary bytes in converter.from()")
        void testRejectMixedEWKBBytesInFrom() {
            // Little-endian EWKB GeometryCollection: header 2D, child 0 2D Point, child 1 3D Point
            byte[] mixedWkb = new byte[]{
                    0x01, 0x07, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00,
                    0x01, 0x01, 0x00, 0x00, 0x00,
                    0, 0, 0, 0, 0, 0, (byte) 0xF0, 0x3F,
                    0, 0, 0, 0, 0, 0, 0x00, 0x40,
                    0x01, 0x01, 0x00, 0x00, (byte) 0x80,
                    0, 0, 0, 0, 0, 0, 0x08, 0x40,
                    0, 0, 0, 0, 0, 0, 0x10, 0x40,
                    0, 0, 0, 0, 0, 0, 0x14, 0x40
            };

            assertThatThrownBy(() -> converter.from(mixedWkb))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Mixed-dimension");
        }
    }

    @Nested
    @DisplayName("Binding SQL Rendering Tests")
    class SqlRenderingTests {

        @Test
        @DisplayName("sql() renders '?::geometry' for geometry and '?::geography' for geography")
        void testStandardSqlRendering() {
            StringBuilder sb = new StringBuilder();
            BindingSQLContext<Geometry> ctx = createBindingSQLContext(null, ParamType.INDEXED, sb);
            binding.sql(ctx);
            assertThat(sb.toString()).isEqualTo("?::geometry");

            sb.setLength(0);
            geographyBinding.sql(ctx);
            assertThat(sb.toString()).isEqualTo("?::geography");
        }

        @Test
        @DisplayName("sql() renders inlined literal when paramType is INLINED")
        void testInlinedSqlRendering() {
            StringBuilder sb = new StringBuilder();
            Point point = gf.createPoint(new Coordinate(1, 2));
            point.setSRID(4326);

            BindingSQLContext<Geometry> ctx = createBindingSQLContext(point, ParamType.INLINED, sb);
            binding.sql(ctx);

            String expectedHex = (String) converter.to(point);
            assertThat(sb.toString()).isEqualTo("'" + expectedHex + "'::geometry");

            sb.setLength(0);
            geographyBinding.sql(ctx);
            assertThat(sb.toString()).isEqualTo("'" + expectedHex + "'::geography");

            // Inlined null
            sb.setLength(0);
            BindingSQLContext<Geometry> nullCtx = createBindingSQLContext(null, ParamType.INLINED, sb);
            binding.sql(nullCtx);
            assertThat(sb.toString()).isEqualTo("NULL::geometry");

            sb.setLength(0);
            geographyBinding.sql(nullCtx);
            assertThat(sb.toString()).isEqualTo("NULL::geography");
        }

        @Test
        @DisplayName("sql() renders named variable when paramType is NAMED")
        void testNamedSqlRendering() {
            StringBuilder sb = new StringBuilder();
            BindingSQLContext<Geometry> ctx = createBindingSQLContext(null, ParamType.NAMED, sb);
            binding.sql(ctx);
            assertThat(sb.toString()).isEqualTo(":1::geometry");

            sb.setLength(0);
            geographyBinding.sql(ctx);
            assertThat(sb.toString()).isEqualTo(":1::geography");
        }
    }

    @Nested
    @DisplayName("Binding Statement & ResultSet Binding Tests")
    class StatementBindingTests {

        @Test
        @DisplayName("set() binds PGobject with type 'geometry' into PreparedStatement")
        void testSetNonNullGeometry() throws Exception {
            Point point = gf.createPoint(new Coordinate(10.0, 20.0));
            point.setSRID(4326);

            AtomicInteger boundIndex = new AtomicInteger();
            AtomicReference<Object> boundObj = new AtomicReference<>();

            PreparedStatement stmt = (PreparedStatement) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {
                        if ("setObject".equals(method.getName())) {
                            boundIndex.set((Integer) args[0]);
                            boundObj.set(args[1]);
                        }
                        return null;
                    });

            BindingSetStatementContext<Geometry> ctx = createSetStatementContext(point, 1, stmt);
            binding.set(ctx);

            assertThat(boundIndex.get()).isEqualTo(1);
            assertThat(boundObj.get()).isInstanceOf(PGobject.class);

            PGobject pg = (PGobject) boundObj.get();
            assertThat(pg.getType()).isEqualTo("geometry");

            Geometry restored = new WKBReader(gf).read(WKBReader.hexToBytes(pg.getValue()));
            assertThat(restored.getSRID()).isEqualTo(4326);
            assertThat(restored.getCoordinate().x).isEqualTo(10.0);
        }

        @Test
        @DisplayName("set() binds PGobject with type 'geography' for geographyBinding")
        void testSetNonNullGeography() throws Exception {
            Point point = gf.createPoint(new Coordinate(10.0, 20.0));
            point.setSRID(4326);

            AtomicReference<Object> boundObj = new AtomicReference<>();

            PreparedStatement stmt = (PreparedStatement) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {
                        if ("setObject".equals(method.getName())) {
                            boundObj.set(args[1]);
                        }
                        return null;
                    });

            BindingSetStatementContext<Geometry> ctx = createSetStatementContext(point, 1, stmt);
            geographyBinding.set(ctx);

            assertThat(boundObj.get()).isInstanceOf(PGobject.class);
            PGobject pg = (PGobject) boundObj.get();
            assertThat(pg.getType()).isEqualTo("geography");
        }

        @Test
        @DisplayName("set() calls setNull with Types.OTHER and spatial type name for null Geometry")
        void testSetNull() throws SQLException {
            AtomicInteger boundIndex = new AtomicInteger();
            AtomicInteger boundType = new AtomicInteger();
            AtomicReference<String> boundTypeName = new AtomicReference<>();

            PreparedStatement stmt = (PreparedStatement) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {
                        if ("setNull".equals(method.getName())) {
                            boundIndex.set((Integer) args[0]);
                            boundType.set((Integer) args[1]);
                            if (args.length > 2) {
                                boundTypeName.set((String) args[2]);
                            }
                        }
                        return null;
                    });

            BindingSetStatementContext<Geometry> ctx = createSetStatementContext(null, 2, stmt);
            binding.set(ctx);

            assertThat(boundIndex.get()).isEqualTo(2);
            assertThat(boundType.get()).isEqualTo(Types.OTHER);
            assertThat(boundTypeName.get()).isEqualTo("geometry");
        }

        @Test
        @DisplayName("get() from ResultSet converts database object to Geometry")
        void testGetResultSet() throws SQLException {
            Point point = gf.createPoint(new Coordinate(5, 10));
            point.setSRID(4326);
            String hex = (String) converter.to(point);

            PGobject pg = new PGobject();
            pg.setType("geometry");
            pg.setValue(hex);

            ResultSet rs = (ResultSet) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> {
                        if ("getObject".equals(method.getName()) && Integer.valueOf(1).equals(args[0])) {
                            return pg;
                        }
                        return null;
                    });

            AtomicReference<Geometry> resultValue = new AtomicReference<>();
            BindingGetResultSetContext<Geometry> ctx = createGetResultSetContext(rs, 1, resultValue);

            binding.get(ctx);

            assertThat(resultValue.get()).isNotNull();
            assertThat(resultValue.get().getSRID()).isEqualTo(4326);
            assertThat(resultValue.get().getCoordinate().x).isEqualTo(5.0);
        }
    }

    @Nested
    @DisplayName("Binding Contract & Equality Tests")
    class ContractTests {

        @Test
        @DisplayName("equals and hashCode contract for Geometry and Geography bindings")
        void testEqualsAndHashCode() {
            PostgisGeometryBinding b1 = new PostgisGeometryBinding();
            PostgisGeometryBinding b2 = new PostgisGeometryBinding();
            PostgisGeographyBinding g1 = new PostgisGeographyBinding();

            assertThat(b1).isEqualTo(b2);
            assertThat(b1.hashCode()).isEqualTo(b2.hashCode());
            assertThat(b1).isNotEqualTo(g1);
            assertThat(b1).isNotEqualTo(null);
        }
    }

    // Helper proxy creators for jOOQ contexts

    private BindingSQLContext<Geometry> createBindingSQLContext(Geometry value, ParamType paramType, StringBuilder sqlBuffer) {
        RenderContext renderContext = (RenderContext) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{RenderContext.class},
                (proxy, method, args) -> {
                    if ("sql".equals(method.getName())) {
                        sqlBuffer.append(args[0]);
                        return proxy;
                    }
                    if ("visit".equals(method.getName())) {
                        QueryPart part = (QueryPart) args[0];
                        if (part != null) {
                            sqlBuffer.append(org.jooq.impl.DSL.using(SQLDialect.POSTGRES).renderInlined(part));
                        }
                        return proxy;
                    }
                    if ("paramType".equals(method.getName())) {
                        return paramType;
                    }
                    return null;
                });

        return (BindingSQLContext<Geometry>) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{BindingSQLContext.class},
                (proxy, method, args) -> {
                    if ("value".equals(method.getName())) return value;
                    if ("render".equals(method.getName())) return renderContext;
                    if ("variable".equals(method.getName())) {
                        return paramType == ParamType.NAMED ? ":1" : "?";
                    }
                    return null;
                });
    }

    private BindingSetStatementContext<Geometry> createSetStatementContext(Geometry value, int index, PreparedStatement stmt) {
        return (BindingSetStatementContext<Geometry>) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{BindingSetStatementContext.class},
                (proxy, method, args) -> {
                    if ("value".equals(method.getName())) return value;
                    if ("index".equals(method.getName())) return index;
                    if ("statement".equals(method.getName())) return stmt;
                    return null;
                });
    }

    private BindingGetResultSetContext<Geometry> createGetResultSetContext(ResultSet rs, int index, AtomicReference<Geometry> out) {
        return (BindingGetResultSetContext<Geometry>) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{BindingGetResultSetContext.class},
                (proxy, method, args) -> {
                    if ("resultSet".equals(method.getName())) return rs;
                    if ("index".equals(method.getName())) return index;
                    if ("value".equals(method.getName()) && args != null && args.length == 1) {
                        out.set((Geometry) args[0]);
                        return null;
                    }
                    return null;
                });
    }
}
