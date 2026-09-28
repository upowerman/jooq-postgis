package top.yunitytech.maven.jooq.binding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateXYM;
import org.locationtech.jts.geom.CoordinateXYZM;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import top.yunitytech.maven.jooq.binding.internal.DimensionAnalyzer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Edge-case regression tests for {@link PostgisCodec}:
 * foreign (non-PostGIS) WKB leaf-flag combinations (both the repairable uniform case and the
 * heterogeneous rejections), ISO WKB type codes, big-endian streams, NaN ordinate semantics,
 * malformed input handling, per-type dimension round-trips, and large geometries.
 * <p>
 * Byte sequences marked "PostGIS-produced" were captured from PostgreSQL 16 / PostGIS 3.4;
 * "foreign" sequences emulate ISO WKB writers whose collection headers under-declare
 * dimension flags (JTS parses every element by its own flags).
 */
class PostgisCodecEdgeCaseTest {

    private final GeometryFactory gf = PostgisCodec.GEOMETRY_FACTORY;

    /** PostGIS output for GEOMETRYCOLLECTION M(POINT M(1 2 3), POINT M(4 5 6)) — top header carries the M flag. */
    private static final String POSTGIS_GC_M =
            "0107000040020000000101000040000000000000f03f000000000000004000000000000008400101000040000000000000104000000000000014400000000000001840";

    /** PostGIS output for LINESTRING Z(0 0 NaN, 1 1 5) — legal NaN Z ordinate, Z flag set. */
    private static final String POSTGIS_LINESTRING_Z_WITH_NAN =
            "01020000800200000000000000000000000000000000000000000000000000f87f000000000000f03f000000000000f03f0000000000001440";

    // ------------------------------------------------------------------
    // Little-endian WKB building helpers (foreign / non-canonical inputs)
    // ------------------------------------------------------------------

    private static byte[] wkbPoint(int type, double... ordinates) {
        ByteBuffer b = leBuffer();
        b.put((byte) 1).putInt(type);
        for (double v : ordinates) {
            b.putDouble(v);
        }
        return toArray(b);
    }

    private static byte[] wkbCollection(int headerType, byte[]... children) {
        ByteBuffer b = leBuffer();
        b.put((byte) 1).putInt(headerType).putInt(children.length);
        for (byte[] child : children) {
            b.put(child);
        }
        return toArray(b);
    }

    private static ByteBuffer leBuffer() {
        return ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] toArray(ByteBuffer b) {
        byte[] out = new byte[b.position()];
        b.flip();
        b.get(out);
        return out;
    }

    @Nested
    @DisplayName("Foreign WKB leaf-flag combinations")
    class ForeignWkbConsistencyTests {

        @Test
        @DisplayName("2D container with uniform M-flagged children decodes to XYM (M must not silently become Z)")
        void foreign2dContainerWithMChildrenDecodesToXym() {
            // ISO WKB leaves the dimension to each element; collection headers may under-declare.
            byte[] wkb = wkbCollection(0x00000007,
                    wkbPoint(0x40000001, 1, 2, 3),
                    wkbPoint(0x40000001, 4, 5, 6));

            Geometry g = PostgisCodec.from(wkb);
            assertThat(g).isInstanceOf(GeometryCollection.class);
            GeometryCollection gc = (GeometryCollection) g;
            assertThat(gc.getNumGeometries()).isEqualTo(2);
            assertThat(gc.getGeometryN(0).getCoordinate()).isInstanceOf(CoordinateXYM.class);
            assertThat(gc.getGeometryN(0).getCoordinate().getM()).isEqualTo(3.0);
            assertThat(Double.isNaN(gc.getGeometryN(0).getCoordinate().getZ())).isTrue();
            assertThat(gc.getGeometryN(1).getCoordinate().getM()).isEqualTo(6.0);

            // same via the hex string path
            assertThat(PostgisCodec.from(WKBWriter.toHex(wkb).toLowerCase())
                    .getGeometryN(0).getCoordinate().getM()).isEqualTo(3.0);
        }

        @Test
        @DisplayName("2D container with uniform ZM-flagged children decodes to XYZM (M preserved)")
        void foreign2dContainerWithZmChildrenDecodesToXyzm() {
            byte[] wkb = wkbCollection(0x00000007,
                    wkbPoint(0xC0000001, 1, 2, 3, 4),
                    wkbPoint(0xC0000001, 5, 6, 7, 8));

            Coordinate c0 = PostgisCodec.from(wkb).getGeometryN(0).getCoordinate();
            assertThat(c0).isInstanceOf(CoordinateXYZM.class);
            assertThat(c0.getZ()).isEqualTo(3.0);
            assertThat(c0.getM()).isEqualTo(4.0);
        }

        @Test
        @DisplayName("Canonical PostGIS GEOMETRYCOLLECTION M (flagged header) decodes to XYM")
        void postgisGcMDecodesToXym() {
            Geometry g = PostgisCodec.from(POSTGIS_GC_M);
            assertThat(g).isInstanceOf(GeometryCollection.class);
            Coordinate c0 = g.getGeometryN(0).getCoordinate();
            assertThat(c0).isInstanceOf(CoordinateXYM.class);
            assertThat(c0.getM()).isEqualTo(3.0);
        }

        @Test
        @DisplayName("Z container with an M child (leaf flags Z vs M) is rejected as mixed-dimension")
        void rejectsZContainerWithMChild() {
            byte[] wkb = wkbCollection(0x80000007,
                    wkbPoint(0x80000001, 1, 2, 3),
                    wkbPoint(0x40000001, 4, 5, 6));

            assertThatThrownBy(() -> PostgisCodec.from(wkb))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Mixed-dimension");
        }

        @Test
        @DisplayName("M container with a 2D child (leaf flags M vs XY) is rejected as mixed-dimension")
        void rejectsMContainerWith2dChild() {
            byte[] wkb = wkbCollection(0x40000007,
                    wkbPoint(0x40000001, 1, 2, 3),
                    wkbPoint(0x00000001, 4, 5));

            assertThatThrownBy(() -> PostgisCodec.from(wkb))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Mixed-dimension");
        }

        @Test
        @DisplayName("Nested foreign WKB (2D outer, M-flagged inner collection) repairs to XYM through nesting")
        void nestedForeignCollectionRepairsToXym() {
            byte[] inner = wkbCollection(0x40000007, wkbPoint(0x40000001, 1, 2, 3));
            byte[] wkb = wkbCollection(0x00000007, inner);

            Geometry g = PostgisCodec.from(wkb);
            assertThat(g).isInstanceOf(GeometryCollection.class);
            Geometry outer = ((GeometryCollection) g).getGeometryN(0);
            assertThat(outer).isInstanceOf(GeometryCollection.class);
            Coordinate c = outer.getGeometryN(0).getCoordinate();
            assertThat(c).isInstanceOf(CoordinateXYM.class);
            assertThat(c.getM()).isEqualTo(3.0);
            assertThat(Double.isNaN(c.getZ())).isTrue();
        }

        @Test
        @DisplayName("Canonical PostGIS GEOMETRYCOLLECTION M containing POINT M EMPTY child decodes")
        void acceptsCanonicalPostgisCollectionMWithEmptyChild() {
            // PG 16.4 / PostGIS 3.4.3: ST_GeomFromEWKT('GEOMETRYCOLLECTION M(POINT M(1 2 3), POINT M EMPTY)')
            // — PostGIS flags EMPTY children with the same dimension markers as the collection.
            String hex = "010700004002000000"
                    + "0101000040000000000000f03f00000000000000400000000000000840"
                    + "0101000040000000000000f87f000000000000f87f000000000000f87f";

            Geometry g = PostgisCodec.from(hex);
            assertThat(g).isInstanceOf(GeometryCollection.class);
            GeometryCollection gc = (GeometryCollection) g;
            assertThat(gc.getGeometryN(0).getCoordinate().getM()).isEqualTo(3.0);
            assertThat(gc.getGeometryN(1).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("Foreign collection with SRID-flagged children parses when leaf dimensions agree")
        void acceptsSridFlaggedChildrenWithConsistentDimensions() {
            // Child carries its own EWKB SRID flag (0x20000001 + srid value); container flags stay 2D
            ByteBuffer child = leBuffer();
            child.put((byte) 1).putInt(0x20000001).putInt(4326).putDouble(1).putDouble(2);
            byte[] wkb = wkbCollection(0x00000007, toArray(child));

            Geometry g = PostgisCodec.from(wkb);
            assertThat(g.getGeometryN(0).getSRID()).isEqualTo(4326);
            assertThat(g.getGeometryN(0).getCoordinate().x).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("ISO WKB type codes beyond POINT")
    class IsoWkbTests {

        @Test
        @DisplayName("ISO WKB POINT Z (type code 1001) decodes with Z retained")
        void isoZPoint() {
            Geometry g = PostgisCodec.from(wkbPoint(1001, 1, 2, 3));
            assertThat(g).isInstanceOf(Point.class);
            assertThat(g.getCoordinate().getZ()).isEqualTo(3.0);
        }

        @Test
        @DisplayName("ISO WKB POINT M (type code 2001) decodes to CoordinateXYM")
        void isoMPoint() {
            Geometry g = PostgisCodec.from(wkbPoint(2001, 1, 2, 3));
            assertThat(g.getCoordinate()).isInstanceOf(CoordinateXYM.class);
            assertThat(g.getCoordinate().getM()).isEqualTo(3.0);
            assertThat(Double.isNaN(g.getCoordinate().getZ())).isTrue();
        }

        @Test
        @DisplayName("ISO WKB POINT ZM (type code 3001) decodes to CoordinateXYZM")
        void isoZmPoint() {
            Geometry g = PostgisCodec.from(wkbPoint(3001, 1, 2, 3, 4));
            Coordinate c = g.getCoordinate();
            assertThat(c).isInstanceOf(CoordinateXYZM.class);
            assertThat(c.getZ()).isEqualTo(3.0);
            assertThat(c.getM()).isEqualTo(4.0);
        }

        @Test
        @DisplayName("ISO WKB LINESTRING Z (type code 1002) decodes with Z retained")
        void isoZLineString() {
            ByteBuffer b = leBuffer();
            b.put((byte) 1).putInt(1002).putInt(2);
            b.putDouble(1).putDouble(2).putDouble(3);
            b.putDouble(4).putDouble(5).putDouble(6);

            Geometry g = PostgisCodec.from(toArray(b));
            assertThat(g).isInstanceOf(LineString.class);
            assertThat(g.getCoordinates()[0].getZ()).isEqualTo(3.0);
            assertThat(g.getCoordinates()[1].getZ()).isEqualTo(6.0);
        }

        @Test
        @DisplayName("Big-endian EWKB POINT M decodes to CoordinateXYM with SRID")
        void bigEndianEwkbMPoint() {
            ByteBuffer b = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN);
            b.put((byte) 0).putInt(0x60000001).putInt(4326);
            b.putDouble(8.0).putDouble(5.0).putDouble(2.5);

            Geometry g = PostgisCodec.from(toArray(b));
            assertThat(g.getCoordinate()).isInstanceOf(CoordinateXYM.class);
            assertThat(g.getSRID()).isEqualTo(4326);
            assertThat(g.getCoordinate().getM()).isEqualTo(2.5);
        }
    }

    @Nested
    @DisplayName("NaN ordinate semantics (PostGIS-compatible)")
    class NanDimensionSemanticsTests {

        @Test
        @DisplayName("PostGIS-produced LINESTRING Z with partial NaN Z reads back without throwing")
        void postgisPartialNanZLineIsReadable() {
            Geometry g = PostgisCodec.from(POSTGIS_LINESTRING_Z_WITH_NAN);

            assertThat(g).isInstanceOf(LineString.class);
            Coordinate[] coords = g.getCoordinates();
            assertThat(coords).hasSize(2);
            assertThat(Double.isNaN(coords[0].getZ())).isTrue();
            assertThat(coords[1].getZ()).isEqualTo(5.0);
        }

        @Test
        @DisplayName("PostGIS NaN Z linestring round-trips keeping the Z dimension flag")
        void postgisPartialNanZLineRoundTripKeepsZFlag() {
            Geometry geom = PostgisCodec.from(POSTGIS_LINESTRING_Z_WITH_NAN);
            String repr = PostgisCodec.toSpatialRepresentation(geom);

            assertThat(PostgisCodec.isHex(repr)).isTrue();
            // Z flag (0x80000000) preserved; JTS WKBWriter always emits the SRID flag too,
            // even for SRID 0, so the big-endian type word starts with 0xA (SRID|Z).
            assertThat(repr).startsWith("00A0");

            Geometry back = PostgisCodec.from(repr);
            assertThat(Double.isNaN(back.getCoordinates()[0].getZ())).isTrue();
            assertThat(back.getCoordinates()[1].getZ()).isEqualTo(5.0);
        }

        @Test
        @DisplayName("All-NaN Z point serializes as 2D (documented plain-Coordinate semantics)")
        void allNanZPointSerializesAs2D() {
            Point p = gf.createPoint(new Coordinate(1, 2, Double.NaN));
            String repr = PostgisCodec.toSpatialRepresentation(p);
            // 2D point type (0x20000001 = JTS always-on SRID flag | POINT, no Z/M dimension flags)
            assertThat(repr).startsWith("0020000001");
        }

        @Test
        @DisplayName("XYZM linestring with partial NaN M round-trips via EWKB with NaN preserved")
        void partialNanMZmRoundTrip() {
            LineString ls = gf.createLineString(new CoordinateArraySequence(new CoordinateXYZM[]{
                    new CoordinateXYZM(1, 2, 3, Double.NaN),
                    new CoordinateXYZM(2, 3, 4, 5)}));
            ls.setSRID(4326);

            String repr = PostgisCodec.toSpatialRepresentation(ls);
            // Since 1.0.5 M geometries serialize as big-endian EWKB (Z|M flags = 0xE0..)
            assertThat(PostgisCodec.isHex(repr)).isTrue();
            assertThat(repr).startsWith("00E0");

            Geometry back = PostgisCodec.from(repr);
            assertThat(back.getCoordinates()[0].getZ()).isEqualTo(3.0);
            assertThat(Double.isNaN(back.getCoordinates()[0].getM())).isTrue();
            assertThat(back.getCoordinates()[1].getM()).isEqualTo(5.0);
            assertThat(back.getSRID()).isEqualTo(4326);
        }

        @Test
        @DisplayName("MULTIPOINT with XY and XYZ points is rejected (collection strictness)")
        void multiPointMixedProfilesRejected() {
            MultiPoint mp = gf.createMultiPoint(new Point[]{
                    gf.createPoint(new Coordinate(1, 2)),
                    gf.createPoint(new Coordinate(3, 4, 5))});

            assertThatThrownBy(() -> PostgisCodec.toSpatialRepresentation(mp))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Mixed-dimension");
        }

        @Test
        @DisplayName("GeometryCollection mixing an XY point and a partial-NaN-Z linestring is rejected")
        void collectionMixingProfilesRejected() {
            Point xy = gf.createPoint(new Coordinate(1, 2));
            LineString nanZ = gf.createLineString(new Coordinate[]{
                    new Coordinate(0, 0, Double.NaN),
                    new Coordinate(1, 1, 5)});
            GeometryCollection gc = gf.createGeometryCollection(new Geometry[]{xy, nanZ});

            assertThatThrownBy(() -> PostgisCodec.toSpatialRepresentation(gc))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Mixed-dimension");
        }

        @Test
        @DisplayName("Empty plain-Coordinate geometry analyzes as EMPTY, all-NaN-Z as XY")
        void analyzerEmptyAndAllNanSemantics() {
            assertThat(DimensionAnalyzer.analyze(gf.createPolygon()).isEmpty()).isTrue();

            LineString allNanZ = gf.createLineString(new Coordinate[]{
                    new Coordinate(0, 0, Double.NaN),
                    new Coordinate(1, 1, Double.NaN)});
            assertThat(DimensionAnalyzer.analyze(allNanZ).getDimension())
                    .isEqualTo(DimensionAnalyzer.CoordinateDimension.XY);
        }
    }

    @Nested
    @DisplayName("Malformed WKB input handling")
    class MalformedWkbTests {

        @Test
        @DisplayName("Truncated WKB is rejected with a clear message")
        void truncatedRejected() {
            assertThatThrownBy(() -> PostgisCodec.fromWkb(new byte[]{0x01, 0x01, 0x00}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Malformed WKB");
        }

        @Test
        @DisplayName("Coordinate count overrunning the byte array is rejected")
        void overrunningCountRejected() {
            // linestring header claiming 100 points with no payload
            byte[] bytes = WKBReader.hexToBytes("010200000064000000");
            assertThatThrownBy(() -> PostgisCodec.fromWkb(bytes))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Malformed WKB");
        }

        @Test
        @DisplayName("Unknown geometry type code is rejected")
        void unknownTypeRejected() {
            // type 42 in the low 16 bits, no flags
            byte[] bytes = WKBReader.hexToBytes("012A000000");
            assertThatThrownBy(() -> PostgisCodec.fromWkb(bytes))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported WKB geometry type");
        }
    }

    @Nested
    @DisplayName("Per-type dimension round-trips")
    class PerTypeDimensionRoundTripTests {

        @Test
        @DisplayName("LineString round-trips across XY / XYZ / XYM / XYZM")
        void lineStringAllDimensions() {
            LineString xy = gf.createLineString(new Coordinate[]{new Coordinate(0, 0), new Coordinate(1, 1)});
            Geometry rxy = PostgisCodec.from(PostgisCodec.toSpatialRepresentation(xy));
            assertThat(rxy).isInstanceOf(LineString.class);
            assertThat(Double.isNaN(rxy.getCoordinates()[0].getZ())).isTrue();
            assertThat(Double.isNaN(rxy.getCoordinates()[0].getM())).isTrue();

            LineString xyz = gf.createLineString(new Coordinate[]{new Coordinate(0, 0, 7), new Coordinate(1, 1, 8)});
            Geometry rxyz = PostgisCodec.from(PostgisCodec.toSpatialRepresentation(xyz));
            assertThat(rxyz.getCoordinates()[0].getZ()).isEqualTo(7.0);

            LineString xym = gf.createLineString(new CoordinateArraySequence(new CoordinateXYM[]{
                    new CoordinateXYM(0, 0, 70), new CoordinateXYM(1, 1, 80)}));
            Geometry rxym = PostgisCodec.from(PostgisCodec.toSpatialRepresentation(xym));
            assertThat(rxym.getCoordinates()[0]).isInstanceOf(CoordinateXYM.class);
            assertThat(rxym.getCoordinates()[0].getM()).isEqualTo(70.0);
            assertThat(Double.isNaN(rxym.getCoordinates()[0].getZ())).isTrue();

            LineString xyzm = gf.createLineString(new CoordinateArraySequence(new CoordinateXYZM[]{
                    new CoordinateXYZM(0, 0, 7, 70), new CoordinateXYZM(1, 1, 8, 80)}));
            Geometry rxyzm = PostgisCodec.from(PostgisCodec.toSpatialRepresentation(xyzm));
            assertThat(rxyzm.getCoordinates()[0].getZ()).isEqualTo(7.0);
            assertThat(rxyzm.getCoordinates()[0].getM()).isEqualTo(70.0);
        }

        @Test
        @DisplayName("MultiPoint round-trips across XY / XYZ / XYM / XYZM")
        void multiPointAllDimensions() {
            MultiPoint xy = gf.createMultiPoint(new Point[]{
                    gf.createPoint(new Coordinate(1, 2)), gf.createPoint(new Coordinate(3, 4))});
            Geometry rxy = PostgisCodec.from(PostgisCodec.toSpatialRepresentation(xy));
            assertThat(rxy).isInstanceOf(MultiPoint.class);
            assertThat(Double.isNaN(rxy.getCoordinates()[0].getZ())).isTrue();

            MultiPoint xyz = gf.createMultiPoint(new Point[]{
                    gf.createPoint(new Coordinate(1, 2, 9)), gf.createPoint(new Coordinate(3, 4, 9))});
            Geometry rxyz = PostgisCodec.from(PostgisCodec.toSpatialRepresentation(xyz));
            assertThat(rxyz.getCoordinates()[0].getZ()).isEqualTo(9.0);

            MultiPoint xym = gf.createMultiPoint(new Point[]{
                    gf.createPoint(new CoordinateXYM(1, 2, 90)), gf.createPoint(new CoordinateXYM(3, 4, 91))});
            Geometry rxym = PostgisCodec.from(PostgisCodec.toSpatialRepresentation(xym));
            assertThat(rxym.getCoordinates()[0]).isInstanceOf(CoordinateXYM.class);
            assertThat(rxym.getCoordinates()[0].getM()).isEqualTo(90.0);
            assertThat(Double.isNaN(rxym.getCoordinates()[0].getZ())).isTrue();

            MultiPoint xyzm = gf.createMultiPoint(new Point[]{
                    gf.createPoint(new CoordinateXYZM(1, 2, 9, 90)), gf.createPoint(new CoordinateXYZM(3, 4, 9, 91))});
            Geometry rxyzm = PostgisCodec.from(PostgisCodec.toSpatialRepresentation(xyzm));
            assertThat(rxyzm.getCoordinates()[0].getZ()).isEqualTo(9.0);
            assertThat(rxyzm.getCoordinates()[0].getM()).isEqualTo(90.0);
        }

        @Test
        @DisplayName("MultiLineString round-trips across XY / XYZ / XYM / XYZM")
        void multiLineStringAllDimensions() {
            MultiLineString xy = gf.createMultiLineString(new LineString[]{
                    gf.createLineString(new Coordinate[]{new Coordinate(0, 0), new Coordinate(1, 1)})});
            Geometry rxy = PostgisCodec.from(PostgisCodec.toSpatialRepresentation(xy));
            assertThat(rxy).isInstanceOf(MultiLineString.class);
            assertThat(Double.isNaN(rxy.getCoordinates()[0].getZ())).isTrue();

            MultiLineString xyz = gf.createMultiLineString(new LineString[]{
                    gf.createLineString(new Coordinate[]{new Coordinate(0, 0, 6), new Coordinate(1, 1, 6)})});
            Geometry rxyz = PostgisCodec.from(PostgisCodec.toSpatialRepresentation(xyz));
            assertThat(rxyz.getCoordinates()[0].getZ()).isEqualTo(6.0);

            MultiLineString xym = gf.createMultiLineString(new LineString[]{
                    gf.createLineString(new CoordinateArraySequence(new CoordinateXYM[]{
                            new CoordinateXYM(0, 0, 60), new CoordinateXYM(1, 1, 61)}))});
            Geometry rxym = PostgisCodec.from(PostgisCodec.toSpatialRepresentation(xym));
            assertThat(rxym.getCoordinates()[0]).isInstanceOf(CoordinateXYM.class);
            assertThat(rxym.getCoordinates()[0].getM()).isEqualTo(60.0);
            assertThat(Double.isNaN(rxym.getCoordinates()[0].getZ())).isTrue();

            MultiLineString xyzm = gf.createMultiLineString(new LineString[]{
                    gf.createLineString(new CoordinateArraySequence(new CoordinateXYZM[]{
                            new CoordinateXYZM(0, 0, 6, 60), new CoordinateXYZM(1, 1, 6, 61)}))});
            Geometry rxyzm = PostgisCodec.from(PostgisCodec.toSpatialRepresentation(xyzm));
            assertThat(rxyzm.getCoordinates()[0].getZ()).isEqualTo(6.0);
            assertThat(rxyzm.getCoordinates()[0].getM()).isEqualTo(60.0);
        }

        @Test
        @DisplayName("GeometryCollection Z containing an empty GeometryCollection child round-trips")
        void geometryCollectionZWithEmptyChildRoundTrip() {
            Point p = gf.createPoint(new Coordinate(1, 2, 3));
            GeometryCollection emptyGc = gf.createGeometryCollection(new Geometry[0]);
            GeometryCollection gc = gf.createGeometryCollection(new Geometry[]{p, emptyGc});
            gc.setSRID(4326);

            Geometry back = PostgisCodec.from(PostgisCodec.toSpatialRepresentation(gc));
            assertThat(back).isInstanceOf(GeometryCollection.class);
            GeometryCollection r = (GeometryCollection) back;
            assertThat(r.getNumGeometries()).isEqualTo(2);
            assertThat(r.getGeometryN(0).getCoordinate().getZ()).isEqualTo(3.0);
            assertThat(r.getGeometryN(1).isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("Large geometry handling")
    class LargeGeometryTests {

        @Test
        @DisplayName("50,000-vertex LineString round-trips losslessly (unit level)")
        void fiftyThousandVertexLineRoundTrip() {
            Coordinate[] coords = new Coordinate[50_000];
            for (int i = 0; i < coords.length; i++) {
                coords[i] = new Coordinate(i * 0.001, i * 0.002);
            }
            LineString ls = gf.createLineString(coords);
            ls.setSRID(4326);

            Geometry back = PostgisCodec.from(PostgisCodec.toSpatialRepresentation(ls));
            assertThat(back).isInstanceOf(LineString.class);
            assertThat(back.getNumPoints()).isEqualTo(50_000);
            assertThat(back.getCoordinate().x).isEqualTo(0.0);
            assertThat(back.getCoordinates()[49_999].y).isEqualTo(49_999 * 0.002);
            assertThat(back.getSRID()).isEqualTo(4326);
        }
    }
}
