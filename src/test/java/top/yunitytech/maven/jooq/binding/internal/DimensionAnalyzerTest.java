package top.yunitytech.maven.jooq.binding.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DimensionAnalyzerTest {

    private final GeometryFactory gf = new GeometryFactory();

    @Nested
    @DisplayName("2D (XY) Dimension Tests")
    class TwoDimensionTests {
        @Test
        @DisplayName("Point 2D")
        void testPoint2D() {
            Point p = gf.createPoint(new Coordinate(1, 2));
            DimensionAnalyzer.Result result = DimensionAnalyzer.analyze(p);

            assertThat(result.getDimension()).isEqualTo(DimensionAnalyzer.CoordinateDimension.XY);
            assertThat(result.hasZ()).isFalse();
            assertThat(result.hasM()).isFalse();
            assertThat(result.isMixed()).isFalse();
            assertThat(result.isEmpty()).isFalse();
            assertThat(result.getCoordinateDimension()).isEqualTo(2);
            assertThat(result.getWkbOutputDimension()).isEqualTo(2);

            DimensionAnalyzer.validateDimensionConsistency(p);
        }

        @Test
        @DisplayName("LineString 2D")
        void testLineString2D() {
            LineString ls = gf.createLineString(new Coordinate[]{
                    new Coordinate(0, 0), new Coordinate(1, 1)
            });
            DimensionAnalyzer.Result result = DimensionAnalyzer.analyze(ls);

            assertThat(result.getDimension()).isEqualTo(DimensionAnalyzer.CoordinateDimension.XY);
            assertThat(result.hasZ()).isFalse();
            assertThat(result.hasM()).isFalse();
            assertThat(result.isMixed()).isFalse();
        }
    }

    @Nested
    @DisplayName("3D (XYZ) Dimension Tests")
    class ThreeDimensionZTests {
        @Test
        @DisplayName("Point 3D XYZ")
        void testPointXYZ() {
            Point p = gf.createPoint(new Coordinate(1, 2, 3));
            DimensionAnalyzer.Result result = DimensionAnalyzer.analyze(p);

            assertThat(result.getDimension()).isEqualTo(DimensionAnalyzer.CoordinateDimension.XYZ);
            assertThat(result.hasZ()).isTrue();
            assertThat(result.hasM()).isFalse();
            assertThat(result.isMixed()).isFalse();
            assertThat(result.isEmpty()).isFalse();
            assertThat(result.getCoordinateDimension()).isEqualTo(3);
            assertThat(result.getWkbOutputDimension()).isEqualTo(3);

            DimensionAnalyzer.validateDimensionConsistency(p);
        }
    }

    @Nested
    @DisplayName("3DM (XYM) Dimension Tests")
    class ThreeDimensionMTests {
        @Test
        @DisplayName("Point 3DM XYM")
        void testPointXYM() {
            Point p = gf.createPoint(new CoordinateXYM(1, 2, 50));
            DimensionAnalyzer.Result result = DimensionAnalyzer.analyze(p);

            assertThat(result.getDimension()).isEqualTo(DimensionAnalyzer.CoordinateDimension.XYM);
            assertThat(result.hasZ()).isFalse();
            assertThat(result.hasM()).isTrue();
            assertThat(result.isMixed()).isFalse();
            assertThat(result.isEmpty()).isFalse();
            assertThat(result.getCoordinateDimension()).isEqualTo(3);
            assertThat(result.getWkbOutputDimension()).isEqualTo(2);

            DimensionAnalyzer.validateDimensionConsistency(p);
        }
    }

    @Nested
    @DisplayName("4D (XYZM) Dimension Tests")
    class FourDimensionTests {
        @Test
        @DisplayName("Point 4D XYZM")
        void testPointXYZM() {
            Point p = gf.createPoint(new CoordinateXYZM(1, 2, 3, 4));
            DimensionAnalyzer.Result result = DimensionAnalyzer.analyze(p);

            assertThat(result.getDimension()).isEqualTo(DimensionAnalyzer.CoordinateDimension.XYZM);
            assertThat(result.hasZ()).isTrue();
            assertThat(result.hasM()).isTrue();
            assertThat(result.isMixed()).isFalse();
            assertThat(result.isEmpty()).isFalse();
            assertThat(result.getCoordinateDimension()).isEqualTo(4);
            assertThat(result.getWkbOutputDimension()).isEqualTo(3);

            DimensionAnalyzer.validateDimensionConsistency(p);
        }
    }

    @Nested
    @DisplayName("EMPTY and Null Geometry Tests")
    class EmptyGeometryTests {
        @Test
        @DisplayName("Null geometry")
        void testNullGeometry() {
            DimensionAnalyzer.Result result = DimensionAnalyzer.analyze(null);

            assertThat(result.getDimension()).isEqualTo(DimensionAnalyzer.CoordinateDimension.EMPTY);
            assertThat(result.isEmpty()).isTrue();
            assertThat(result.isMixed()).isFalse();
            assertThat(result.hasZ()).isFalse();
            assertThat(result.hasM()).isFalse();

            DimensionAnalyzer.validateDimensionConsistency(null);
        }

        @Test
        @DisplayName("Empty Point and Polygon")
        void testEmptyPointAndPolygon() {
            Point emptyPoint = gf.createPoint();
            DimensionAnalyzer.Result pResult = DimensionAnalyzer.analyze(emptyPoint);
            assertThat(pResult.isEmpty()).isTrue();
            assertThat(pResult.isMixed()).isFalse();
            DimensionAnalyzer.validateDimensionConsistency(emptyPoint);

            Polygon emptyPoly = gf.createPolygon();
            DimensionAnalyzer.Result polyResult = DimensionAnalyzer.analyze(emptyPoly);
            assertThat(polyResult.isEmpty()).isTrue();
            assertThat(polyResult.isMixed()).isFalse();
            DimensionAnalyzer.validateDimensionConsistency(emptyPoly);
        }

        @Test
        @DisplayName("GeometryCollection with empty component and 2D point")
        void testCollectionWithEmptyComponent() {
            Point empty = gf.createPoint();
            Point p2d = gf.createPoint(new Coordinate(1, 2));
            GeometryCollection gc = gf.createGeometryCollection(new Geometry[]{empty, p2d});

            DimensionAnalyzer.Result result = DimensionAnalyzer.analyze(gc);
            assertThat(result.getDimension()).isEqualTo(DimensionAnalyzer.CoordinateDimension.XY);
            assertThat(result.isMixed()).isFalse();
            assertThat(result.isEmpty()).isFalse();
            assertThat(result.hasZ()).isFalse();
            assertThat(result.hasM()).isFalse();
            DimensionAnalyzer.validateDimensionConsistency(gc);
        }
    }

    @Nested
    @DisplayName("Mixed Dimension Rejection Tests")
    class MixedDimensionTests {
        @Test
        @DisplayName("Collection mixing 2D and 3D")
        void testMixed2DAnd3D() {
            Point p2d = gf.createPoint(new Coordinate(1, 2));
            Point p3d = gf.createPoint(new Coordinate(3, 4, 5));
            GeometryCollection gc = gf.createGeometryCollection(new Geometry[]{p2d, p3d});

            DimensionAnalyzer.Result result = DimensionAnalyzer.analyze(gc);
            assertThat(result.isMixed()).isTrue();
            assertThat(result.getDimension()).isEqualTo(DimensionAnalyzer.CoordinateDimension.MIXED);

            assertThatThrownBy(() -> DimensionAnalyzer.validateDimensionConsistency(gc))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Mixed-dimension");
        }

        @Test
        @DisplayName("Collection mixing 3D and 3DM")
        void testMixed3DAnd3DM() {
            Point p3d = gf.createPoint(new Coordinate(1, 2, 3));
            Point pxym = gf.createPoint(new CoordinateXYM(4, 5, 6));
            GeometryCollection gc = gf.createGeometryCollection(new Geometry[]{p3d, pxym});

            DimensionAnalyzer.Result result = DimensionAnalyzer.analyze(gc);
            assertThat(result.isMixed()).isTrue();

            assertThatThrownBy(() -> DimensionAnalyzer.validateDimensionConsistency(gc))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Mixed-dimension");
        }

        @Test
        @DisplayName("Collection mixing 2D and 4D")
        void testMixed2DAnd4D() {
            Point p2d = gf.createPoint(new Coordinate(1, 2));
            Point pxyzm = gf.createPoint(new CoordinateXYZM(3, 4, 5, 6));
            GeometryCollection gc = gf.createGeometryCollection(new Geometry[]{p2d, pxyzm});

            DimensionAnalyzer.Result result = DimensionAnalyzer.analyze(gc);
            assertThat(result.isMixed()).isTrue();

            assertThatThrownBy(() -> DimensionAnalyzer.validateDimensionConsistency(gc))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Mixed-dimension");
        }

        @Test
        @DisplayName("LineString with partial NaN Z resolves to XYZ (OR semantics, NaN preserved)")
        void testLineStringWithPartialNanZ() {
            Coordinate[] coords = new Coordinate[]{
                    new Coordinate(1, 2),
                    new Coordinate(3, 4, 5)
            };
            LineString ls = gf.createLineString(coords);

            DimensionAnalyzer.Result result = DimensionAnalyzer.analyze(ls);
            assertThat(result.isMixed()).isFalse();
            assertThat(result.getDimension()).isEqualTo(DimensionAnalyzer.CoordinateDimension.XYZ);
            assertThat(result.hasZ()).isTrue();

            // no exception: NaN is a legal PostGIS ordinate value, not a dimension signal
            DimensionAnalyzer.validateDimensionConsistency(ls);
        }

        @Test
        @DisplayName("Plain all-NaN-Z geometry resolves to XY")
        void testPlainAllNanZResolvesToXY() {
            LineString ls = gf.createLineString(new Coordinate[]{
                    new Coordinate(1, 2, Double.NaN), new Coordinate(3, 4, Double.NaN)
            });
            assertThat(DimensionAnalyzer.analyze(ls).getDimension())
                    .isEqualTo(DimensionAnalyzer.CoordinateDimension.XY);
        }

        @Test
        @DisplayName("Typed XYM with NaN M keeps the M dimension (metadata wins over values)")
        void testXymWithNanMKeepsM() {
            Point p = gf.createPoint(new CoordinateXYM(1, 2, Double.NaN));
            DimensionAnalyzer.Result result = DimensionAnalyzer.analyze(p);
            assertThat(result.getDimension()).isEqualTo(DimensionAnalyzer.CoordinateDimension.XYM);
            assertThat(result.hasM()).isTrue();
            assertThat(result.hasZ()).isFalse();
        }

        @Test
        @DisplayName("Typed XYZM with NaN M keeps both dimensions")
        void testXyzmWithNanMKeepsZm() {
            Point p = gf.createPoint(new CoordinateXYZM(1, 2, 3, Double.NaN));
            DimensionAnalyzer.Result result = DimensionAnalyzer.analyze(p);
            assertThat(result.getDimension()).isEqualTo(DimensionAnalyzer.CoordinateDimension.XYZM);
        }

        @Test
        @DisplayName("Nested collection with mixed dimensions")
        void testNestedMixedCollection() {
            Point p2d = gf.createPoint(new Coordinate(1, 2));
            Point p3d = gf.createPoint(new Coordinate(3, 4, 5));
            GeometryCollection inner = gf.createGeometryCollection(new Geometry[]{p3d});
            GeometryCollection root = gf.createGeometryCollection(new Geometry[]{p2d, inner});

            DimensionAnalyzer.Result result = DimensionAnalyzer.analyze(root);
            assertThat(result.isMixed()).isTrue();

            assertThatThrownBy(() -> DimensionAnalyzer.validateDimensionConsistency(root))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Mixed-dimension");
        }
    }
}
