package top.yunitytech.maven.jooq.binding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.postgresql.util.PGobject;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgisCodecTest {

    private final GeometryFactory gf = PostgisCodec.GEOMETRY_FACTORY;

    @Test
    @DisplayName("Codec: Null and empty input handling")
    void testNullAndEmptyInputs() throws ParseException {
        assertThat(PostgisCodec.from(null)).isNull();
        assertThat(PostgisCodec.from("")).isNull();
        assertThat(PostgisCodec.from("   ")).isNull();
        assertThat(PostgisCodec.fromWkb(null)).isNull();
        assertThat(PostgisCodec.fromWkb(new byte[0])).isNull();
        assertThat(PostgisCodec.fromWkt(null)).isNull();
        assertThat(PostgisCodec.fromWkt("")).isNull();
        assertThat(PostgisCodec.toSpatialRepresentation(null)).isNull();
    }

    @Test
    @DisplayName("Codec: Deserialize from PGobject")
    void testFromPgObject() throws SQLException {
        PGobject pgObj = new PGobject();
        pgObj.setType("geometry");
        pgObj.setValue("SRID=4326;POINT(116.4 39.9)");

        Geometry geom = PostgisCodec.from(pgObj);
        assertThat(geom).isNotNull().isInstanceOf(Point.class);
        assertThat(geom.getSRID()).isEqualTo(4326);
        assertThat(geom.getCoordinate().x).isEqualTo(116.4);
        assertThat(geom.getCoordinate().y).isEqualTo(39.9);
    }

    @Test
    @DisplayName("Codec: Deserialize from binary byte[] WKB")
    void testFromBytes() throws ParseException {
        Point point = gf.createPoint(new Coordinate(10, 20));
        point.setSRID(4326);

        String hex = PostgisCodec.toSpatialRepresentation(point);
        byte[] bytes = WKBReader.hexToBytes(hex);

        Geometry parsed = PostgisCodec.from(bytes);
        assertThat(parsed).isNotNull().isInstanceOf(Point.class);
        assertThat(parsed.getSRID()).isEqualTo(4326);
        assertThat(parsed.getCoordinate().x).isEqualTo(10.0);
        assertThat(parsed.getCoordinate().y).isEqualTo(20.0);
    }

    @Test
    @DisplayName("Codec: Full round-trip for 2D (XY)")
    void testRoundTripXY() {
        Point point = gf.createPoint(new Coordinate(116.4074, 39.9042));
        point.setSRID(4326);

        String repr = PostgisCodec.toSpatialRepresentation(point);
        assertThat(PostgisCodec.isHex(repr)).isTrue();

        Geometry decoded = PostgisCodec.from(repr);
        assertThat(decoded).isNotNull().isInstanceOf(Point.class);
        assertThat(decoded.getSRID()).isEqualTo(4326);
        assertThat(decoded.getCoordinate().x).isEqualTo(116.4074);
        assertThat(decoded.getCoordinate().y).isEqualTo(39.9042);
        assertThat(Double.isNaN(decoded.getCoordinate().getZ())).isTrue();
    }

    @Test
    @DisplayName("Codec: Full round-trip for 3D (XYZ)")
    void testRoundTripXYZ() {
        Point point = gf.createPoint(new Coordinate(116.4074, 39.9042, 8848.86));
        point.setSRID(4326);

        String repr = PostgisCodec.toSpatialRepresentation(point);
        assertThat(PostgisCodec.isHex(repr)).isTrue();

        Geometry decoded = PostgisCodec.from(repr);
        assertThat(decoded).isNotNull().isInstanceOf(Point.class);
        assertThat(decoded.getSRID()).isEqualTo(4326);
        assertThat(decoded.getCoordinate().getZ()).isEqualTo(8848.86);
        assertThat(Double.isNaN(decoded.getCoordinate().getM())).isTrue();
    }

    @Test
    @DisplayName("Codec: Full round-trip for 3DM (XYM)")
    void testRoundTripXYM() {
        Point point = gf.createPoint(new CoordinateXYM(116.4, 39.9, 1695888000.0));
        point.setSRID(4326);

        String repr = PostgisCodec.toSpatialRepresentation(point);
        assertThat(repr).startsWith("SRID=4326;POINT M");

        Geometry decoded = PostgisCodec.from(repr);
        assertThat(decoded).isNotNull().isInstanceOf(Point.class);
        assertThat(decoded.getSRID()).isEqualTo(4326);
        assertThat(Double.isNaN(decoded.getCoordinate().getZ())).isTrue();
        assertThat(decoded.getCoordinate().getM()).isEqualTo(1695888000.0);
    }

    @Test
    @DisplayName("Codec: Full round-trip for 4D (XYZM)")
    void testRoundTripXYZM() {
        CoordinateXYZM[] coords = new CoordinateXYZM[]{
                new CoordinateXYZM(0, 0, 10, 100),
                new CoordinateXYZM(1, 0, 20, 200),
                new CoordinateXYZM(1, 1, 30, 300),
                new CoordinateXYZM(0, 1, 40, 400),
                new CoordinateXYZM(0, 0, 10, 100)
        };
        Polygon poly = gf.createPolygon(new CoordinateArraySequence(coords));
        poly.setSRID(4490);

        String repr = PostgisCodec.toSpatialRepresentation(poly);
        assertThat(repr).startsWith("SRID=4490;POLYGON ZM");

        Geometry decoded = PostgisCodec.from(repr);
        assertThat(decoded).isNotNull().isInstanceOf(Polygon.class);
        assertThat(decoded.getSRID()).isEqualTo(4490);
        Coordinate c0 = decoded.getCoordinates()[0];
        assertThat(c0.getZ()).isEqualTo(10.0);
        assertThat(c0.getM()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("Codec: EMPTY geometry handling")
    void testEmptyGeometry() {
        Point emptyPoint = gf.createPoint((Coordinate) null);
        emptyPoint.setSRID(4326);

        String repr = PostgisCodec.toSpatialRepresentation(emptyPoint);
        assertThat(PostgisCodec.isHex(repr)).isTrue();

        Geometry decoded = PostgisCodec.from(repr);
        assertThat(decoded).isNotNull().isInstanceOf(Point.class);
        assertThat(decoded.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("Codec: Reject mixed-dimension geometries")
    void testRejectMixedDimension() {
        Point p2d = gf.createPoint(new Coordinate(1, 2));
        Point p3d = gf.createPoint(new Coordinate(3, 4, 5));
        GeometryCollection mixed = gf.createGeometryCollection(new Geometry[]{p2d, p3d});

        assertThatThrownBy(() -> PostgisCodec.toSpatialRepresentation(mixed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mixed-dimension");

        assertThatThrownBy(() -> PostgisCodec.validateDimensionConsistency(mixed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mixed-dimension");
    }

    @Test
    @DisplayName("Codec: Parse standard WKT")
    void testStandardWkt() throws ParseException {
        Geometry geom = PostgisCodec.fromWkt("POINT(10 20)");
        assertThat(geom).isNotNull().isInstanceOf(Point.class);
        assertThat(geom.getCoordinate().x).isEqualTo(10.0);
        assertThat(geom.getCoordinate().y).isEqualTo(20.0);
    }

    @Test
    @DisplayName("Codec: Malformed input throws expected exception")
    void testMalformedInput() {
        assertThatThrownBy(() -> PostgisCodec.from("NOT_A_GEOMETRY"))
                .isInstanceOf(RuntimeException.class);

        assertThatThrownBy(() -> PostgisCodec.fromWkb(new byte[]{0, 1, 2, 3}))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("Codec: fixWktEmptySpacing normalizes dimensioned empty WKT")
    void testFixWktEmptySpacing() {
        assertThat(PostgisCodec.fixWktEmptySpacing("POINT ZMEMPTY")).isEqualTo("POINT ZM EMPTY");
        assertThat(PostgisCodec.fixWktEmptySpacing("POINT ZEMPTY")).isEqualTo("POINT Z EMPTY");
        assertThat(PostgisCodec.fixWktEmptySpacing("POINT MEMPTY")).isEqualTo("POINT M EMPTY");
        assertThat(PostgisCodec.fixWktEmptySpacing("POINT EMPTY")).isEqualTo("POINT EMPTY");
    }
}
