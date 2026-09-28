package top.yunitytech.maven.jooq.binding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Structural WKB/EWKB decoding tests: foreign (non-PostGIS) producers, ISO WKB type codes,
 * big-endian streams, and NaN ordinate preservation.
 * <p>
 * Byte sequences marked "PostGIS-produced" were captured from PostgreSQL 16 / PostGIS 3.4;
 * "foreign" sequences emulate ISO WKB writers whose collection headers under-declare
 * dimension flags (JTS parses every element by its own flags).
 */
class PostgisCodecWkbStructuralTest {

    private final GeometryFactory gf = PostgisCodec.GEOMETRY_FACTORY;

    /** PostGIS output for GEOMETRYCOLLECTION M(POINT M(1 2 3), POINT M(4 5 6)) — top header carries the M flag. */
    private static final String POSTGIS_GC_M = "0107000040020000000101000040000000000000f03f000000000000004000000000000008400101000040000000000000104000000000000014400000000000001840";

    /** Foreign WKB: GeometryCollection with a 2D header but M-flagged POINT children. */
    private static final String FOREIGN_GC_2D_HEADER_M_CHILDREN = "0107000000020000000101000040000000000000f03f000000000000004000000000000008400101000040000000000000104000000000000014400000000000001840";

    /** Foreign WKB: GeometryCollection with a 2D header but ZM-flagged POINT children. */
    private static final String FOREIGN_GC_2D_HEADER_ZM_CHILDREN = "010700000002000000" + "01010000C0000000000000f03f000000000000004000000000000008400000000000001040" + "01010000C0000000000000144000000000000018400000000000001c400000000000002040";

    /** PostGIS output for SRID=4326;LINESTRING Z(0 0 NaN, 1 1 5) — legal NaN Z ordinate. */
    private static final String POSTGIS_LINESTRING_Z_WITH_NAN = "01020000800200000000000000000000000000000000000000000000000000f87f000000000000f03f000000000000f03f0000000000001440";

    /** ISO WKB (ST_AsBinary output style): POINT Z(1 2 3), type code 1001. */
    private static final String ISO_POINT_Z = "01e9030000000000000000f03f00000000000000400000000000000840";

    /** Big-endian EWKB: SRID=4326;POINT M(8 5 2.5). */
    private static final String BIG_ENDIAN_POINT_M = "0060000001000010e6402000000000000040140000000000004004000000000000";

    @Nested
    @DisplayName("Foreign WKB with under-declared collection headers (P1 regression)")
    class ForeignWkbTests {

        @Test
        @DisplayName("2D collection header + M children decodes to XYM (M not re-interpreted as Z)")
        void foreignGcWithMChildrenDecodesToXym() {
            Geometry geom = PostgisCodec.from(FOREIGN_GC_2D_HEADER_M_CHILDREN);

            assertThat(geom).isInstanceOf(GeometryCollection.class);
            GeometryCollection gc = (GeometryCollection) geom;
            assertThat(gc.getNumGeometries()).isEqualTo(2);

            Coordinate c0 = gc.getGeometryN(0).getCoordinate();
            assertThat(c0).isInstanceOf(CoordinateXYM.class);
            assertThat(c0.x).isEqualTo(1.0);
            assertThat(c0.y).isEqualTo(2.0);
            assertThat(c0.getM()).isEqualTo(3.0);
            assertThat(Double.isNaN(c0.getZ())).isTrue();

            Coordinate c1 = gc.getGeometryN(1).getCoordinate();
            assertThat(c1).isInstanceOf(CoordinateXYM.class);
            assertThat(c1.getM()).isEqualTo(6.0);
        }

        @Test
        @DisplayName("2D collection header + ZM children decodes to XYZM (M preserved)")
        void foreignGcWithZmChildrenDecodesToXyzm() {
            Geometry geom = PostgisCodec.from(FOREIGN_GC_2D_HEADER_ZM_CHILDREN);

            GeometryCollection gc = (GeometryCollection) geom;
            assertThat(gc.getNumGeometries()).isEqualTo(2);

            Coordinate c0 = gc.getGeometryN(0).getCoordinate();
            assertThat(c0).isInstanceOf(CoordinateXYZM.class);
            assertThat(c0.getZ()).isEqualTo(3.0);
            assertThat(c0.getM()).isEqualTo(4.0);

            Coordinate c1 = gc.getGeometryN(1).getCoordinate();
            assertThat(c1).isInstanceOf(CoordinateXYZM.class);
            assertThat(c1.getZ()).isEqualTo(7.0);
            assertThat(c1.getM()).isEqualTo(8.0);
        }

        @Test
        @DisplayName("PostGIS-produced GC M (flagged header) still decodes to XYM")
        void postgisGcMDecodesToXym() {
            Geometry geom = PostgisCodec.from(POSTGIS_GC_M);
            Coordinate c0 = geom.getGeometryN(0).getCoordinate();
            assertThat(c0).isInstanceOf(CoordinateXYM.class);
            assertThat(c0.getM()).isEqualTo(3.0);
        }

        @Test
        @DisplayName("Leaf flags disagreeing (2D leaf vs Z leaf) are rejected as mixed-dimension")
        void inconsistentLeafFlagsRejected() {
            // GC 2D header, child 0 = POINT 2D(1 2), child 1 = POINT Z(3 4 5)
            byte[] mixed = new byte[]{
                    0x01, 0x07, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00,
                    0x01, 0x01, 0x00, 0x00, 0x00,
                    0, 0, 0, 0, 0, 0, (byte) 0xF0, 0x3F,
                    0, 0, 0, 0, 0, 0, 0x00, 0x40,
                    0x01, 0x01, 0x00, 0x00, (byte) 0x80,
                    0, 0, 0, 0, 0, 0, 0x08, 0x40,
                    0, 0, 0, 0, 0, 0, 0x10, 0x40,
                    0, 0, 0, 0, 0, 0, 0x14, 0x40
            };
            assertThatThrownBy(() -> PostgisCodec.from(mixed))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Mixed-dimension");
        }

        @Test
        @DisplayName("Foreign M collection byte[] input (not hex string) also decodes to XYM")
        void foreignGcByteArrayDecodesToXym() {
            Geometry geom = PostgisCodec.from(WKBReader.hexToBytes(FOREIGN_GC_2D_HEADER_M_CHILDREN));
            assertThat(geom.getGeometryN(0).getCoordinate()).isInstanceOf(CoordinateXYM.class);
        }
    }

    @Nested
    @DisplayName("NaN ordinate preservation (P1 regression)")
    class NanOrdinateTests {

        @Test
        @DisplayName("PostGIS-produced LINESTRING Z with NaN Z reads back without throwing")
        void nanZLinestringFromPostgisReadsBack() {
            Geometry geom = PostgisCodec.from(POSTGIS_LINESTRING_Z_WITH_NAN);

            assertThat(geom).isInstanceOf(LineString.class);
            Coordinate[] coords = geom.getCoordinates();
            assertThat(coords).hasSize(2);
            assertThat(Double.isNaN(coords[0].getZ())).isTrue();
            assertThat(coords[1].getZ()).isEqualTo(5.0);
        }

        @Test
        @DisplayName("NaN Z linestring round-trips through the codec keeping the Z dimension")
        void nanZLinestringRoundTrip() {
            Geometry geom = PostgisCodec.from(POSTGIS_LINESTRING_Z_WITH_NAN);
            String repr = PostgisCodec.toSpatialRepresentation(geom);

            assertThat(PostgisCodec.isHex(repr)).isTrue();
            // Z flag (0x80000000) preserved; JTS WKBWriter always emits the SRID flag too,
            // even for SRID 0, so the big-endian type word starts with 0xA (SRID|Z).
            assertThat(repr).startsWith("00A0");

            Geometry decoded = PostgisCodec.from(repr);
            assertThat(Double.isNaN(decoded.getCoordinates()[0].getZ())).isTrue();
            assertThat(decoded.getCoordinates()[1].getZ()).isEqualTo(5.0);
        }

        @Test
        @DisplayName("All-NaN Z geometry writes as 2D (documented plain-Coordinate semantics)")
        void allNanZWritesAs2D() {
            Point p = gf.createPoint(new Coordinate(1, 2, Double.NaN));
            String repr = PostgisCodec.toSpatialRepresentation(p);
            // 2D point type (0x20000001 = JTS always-on SRID flag | POINT, no Z/M dimension flags)
            assertThat(repr).startsWith("0020000001");
        }
    }

    @Nested
    @DisplayName("ISO WKB and big-endian input")
    class IsoAndBigEndianTests {

        @Test
        @DisplayName("ISO WKB POINT Z (type code 1001) decodes with Z preserved")
        void isoPointZ() {
            Geometry geom = PostgisCodec.from(ISO_POINT_Z);
            assertThat(geom).isInstanceOf(Point.class);
            assertThat(geom.getCoordinate().getZ()).isEqualTo(3.0);
        }

        @Test
        @DisplayName("ISO WKB POINT ZM (type code 3001) decodes to CoordinateXYZM")
        void isoPointZm() {
            // little-endian ISO: 01 + type 3001 (0x0BB9) + x=1 y=2 z=3 m=4
            byte[] bytes = WKBReader.hexToBytes("01b90b0000" + isoPointCoords(1, 2, 3, 4));
            Geometry geom = PostgisCodec.from(bytes);
            Coordinate c = geom.getCoordinate();
            assertThat(c).isInstanceOf(CoordinateXYZM.class);
            assertThat(c.getZ()).isEqualTo(3.0);
            assertThat(c.getM()).isEqualTo(4.0);
        }

        @Test
        @DisplayName("Big-endian EWKB POINT M decodes to CoordinateXYM with SRID")
        void bigEndianPointM() throws ParseException {
            Geometry geom = PostgisCodec.fromWkb(WKBReader.hexToBytes(BIG_ENDIAN_POINT_M));
            Coordinate c = geom.getCoordinate();
            assertThat(c).isInstanceOf(CoordinateXYM.class);
            assertThat(c.x).isEqualTo(8.0);
            assertThat(c.y).isEqualTo(5.0);
            assertThat(c.getM()).isEqualTo(2.5);
            assertThat(geom.getSRID()).isEqualTo(4326);
        }

        private String isoPointCoords(double... values) {
            StringBuilder sb = new StringBuilder();
            for (double v : values) {
                long bits = Double.doubleToLongBits(v);
                for (int i = 0; i < 8; i++) {
                    sb.append(String.format("%02x", (bits >> (i * 8)) & 0xFF));
                }
            }
            return sb.toString();
        }
    }

    @Nested
    @DisplayName("Malformed input handling")
    class MalformedInputTests {

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
            // type 42 (in low 16 bits), no flags
            byte[] bytes = WKBReader.hexToBytes("012A000000");
            assertThatThrownBy(() -> PostgisCodec.fromWkb(bytes))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported WKB geometry type");
        }
    }
}
