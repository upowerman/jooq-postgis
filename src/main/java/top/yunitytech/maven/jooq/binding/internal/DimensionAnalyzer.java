package top.yunitytech.maven.jooq.binding.internal;

import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;

import java.io.Serializable;
import java.util.Objects;

/**
 * Robust spatial coordinate dimension detector and validator.
 * <p>
 * Supports 2D (XY), 3D (XYZ), 3DM (XYM), 4D (XYZM), EMPTY, and MIXED coordinate dimensions.
 * <p>
 * Dimension detection rules (since 1.0.5):
 * <ul>
 *     <li>Sequences that carry explicit dimension metadata (typed {@code CoordinateXYM}/{@code CoordinateXYZM}
 *     arrays, packed sequences with measures) are trusted as-is: the declared dimension wins over ordinate values.</li>
 *     <li>Plain 3-ordinate sequences (the JTS default for {@code Coordinate} arrays) cannot declare intent,
 *     so Z presence is value-based: if <em>any</em> coordinate has a non-NaN Z the geometry is XYZ;
 *     NaN values are legal PostGIS ordinates and are preserved verbatim (OR semantics).</li>
 *     <li>4-ordinate sequences without measures metadata are interpreted as XYZM.</li>
 *     <li>Within a single (non-collection) geometry, sequences are combined with OR semantics —
 *     partial NaN patterns are not "mixed dimension".</li>
 *     <li>Inside a {@link GeometryCollection} all non-empty siblings must resolve to the same dimension
 *     profile; otherwise the geometry is MIXED and rejected. This mirrors PostGIS, which rejects
 *     dimension-heterogeneous collections ({@code Dimensions mismatch in lwcollection}).</li>
 * </ul>
 *
 * @author gaoyunfeng
 */
public final class DimensionAnalyzer {

    private static final String MIXED_DIMENSION_ERROR_MESSAGE =
            "Mixed-dimension geometry is not supported: geometry components have inconsistent coordinate dimensions (e.g. XY mixed with XYZ/XYM/XYZM).";

    private static final String UNSUPPORTED_DIMENSION_ERROR_TEMPLATE =
            "Unsupported coordinate dimension %d in coordinate sequence (supported: 2-4).";

    private DimensionAnalyzer() {
        // Utility class
    }

    /**
     * Enumeration of detected coordinate dimensions.
     */
    public enum CoordinateDimension {
        XY(2, false, false),
        XYZ(3, true, false),
        XYM(3, false, true),
        XYZM(4, true, true),
        EMPTY(0, false, false),
        MIXED(-1, false, false);

        private final int coordinateDimension;
        private final boolean hasZ;
        private final boolean hasM;

        CoordinateDimension(int coordinateDimension, boolean hasZ, boolean hasM) {
            this.coordinateDimension = coordinateDimension;
            this.hasZ = hasZ;
            this.hasM = hasM;
        }

        public int getCoordinateDimension() {
            return coordinateDimension;
        }

        public boolean hasZ() {
            return hasZ;
        }

        public boolean hasM() {
            return hasM;
        }
    }

    /**
     * Immutable analysis result of a geometry's coordinate dimensions.
     */
    public static final class Result implements Serializable {
        private static final long serialVersionUID = 1L;

        public static final Result EMPTY = new Result(CoordinateDimension.EMPTY, false, false, false, true);
        public static final Result MIXED = new Result(CoordinateDimension.MIXED, false, false, true, false);
        public static final Result XY = new Result(CoordinateDimension.XY, false, false, false, false);
        public static final Result XYZ = new Result(CoordinateDimension.XYZ, true, false, false, false);
        public static final Result XYM = new Result(CoordinateDimension.XYM, false, true, false, false);
        public static final Result XYZM = new Result(CoordinateDimension.XYZM, true, true, false, false);

        private final CoordinateDimension dimension;
        private final boolean hasZ;
        private final boolean hasM;
        private final boolean mixed;
        private final boolean empty;

        private Result(CoordinateDimension dimension, boolean hasZ, boolean hasM, boolean mixed, boolean empty) {
            this.dimension = dimension;
            this.hasZ = hasZ;
            this.hasM = hasM;
            this.mixed = mixed;
            this.empty = empty;
        }

        public static Result of(boolean hasZ, boolean hasM) {
            if (hasZ && hasM) {
                return XYZM;
            } else if (hasZ) {
                return XYZ;
            } else if (hasM) {
                return XYM;
            } else {
                return XY;
            }
        }

        public CoordinateDimension getDimension() {
            return dimension;
        }

        public boolean hasZ() {
            return hasZ;
        }

        public boolean hasM() {
            return hasM;
        }

        public boolean isMixed() {
            return mixed;
        }

        public boolean isEmpty() {
            return empty;
        }

        /**
         * Returns the coordinate dimension (2 for XY, 3 for XYZ/XYM, 4 for XYZM, 0 for EMPTY, -1 for MIXED).
         */
        public int getCoordinateDimension() {
            return dimension.getCoordinateDimension();
        }

        /**
         * Returns the WKB output dimension (3 for XYZ/XYZM with Z, 2 for XY/XYM/EMPTY).
         */
        public int getWkbOutputDimension() {
            return hasZ ? 3 : 2;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Result result = (Result) o;
            return hasZ == result.hasZ &&
                    hasM == result.hasM &&
                    mixed == result.mixed &&
                    empty == result.empty &&
                    dimension == result.dimension;
        }

        @Override
        public int hashCode() {
            return Objects.hash(dimension, hasZ, hasM, mixed, empty);
        }

        @Override
        public String toString() {
            return "DimensionAnalysis.Result{" +
                    "dimension=" + dimension +
                    ", hasZ=" + hasZ +
                    ", hasM=" + hasM +
                    ", mixed=" + mixed +
                    ", empty=" + empty +
                    '}';
        }
    }

    /**
     * Analyzes the coordinate dimension structure of the given geometry.
     *
     * @param geom geometry to inspect
     * @return analysis result describing coordinate dimension, presence of Z/M, and consistency
     * @throws IllegalArgumentException if a coordinate sequence declares an unsupported dimension (not 2-4)
     */
    public static Result analyze(Geometry geom) {
        if (geom == null || geom.isEmpty()) {
            return Result.EMPTY;
        }
        if (geom instanceof GeometryCollection) {
            return analyzeCollection((GeometryCollection) geom);
        }
        DimensionFilter filter = new DimensionFilter();
        geom.apply(filter);
        if (!filter.hasSequence()) {
            return Result.EMPTY;
        }
        return Result.of(filter.hasZ(), filter.hasM());
    }

    /**
     * Collections (including nested ones) require every non-empty child to resolve to the same
     * dimension profile, matching PostGIS behaviour for collections.
     */
    private static Result analyzeCollection(GeometryCollection gc) {
        Result profile = null;
        for (int i = 0; i < gc.getNumGeometries(); i++) {
            Result child = analyze(gc.getGeometryN(i));
            if (child.isEmpty()) {
                continue;
            }
            if (profile == null) {
                profile = child;
            } else if (profile.hasZ() != child.hasZ() || profile.hasM() != child.hasM()) {
                return Result.MIXED;
            }
        }
        return profile != null ? profile : Result.EMPTY;
    }

    /**
     * Validates that all geometry components resolve to a consistent coordinate dimension.
     *
     * @param geom geometry to validate
     * @throws IllegalArgumentException if collection siblings have inconsistent coordinate dimensions,
     *                                  or if a coordinate sequence declares an unsupported dimension
     */
    public static void validateDimensionConsistency(Geometry geom) {
        if (geom == null || geom.isEmpty()) {
            return;
        }
        Result result = analyze(geom);
        if (result.isMixed()) {
            throw new IllegalArgumentException(MIXED_DIMENSION_ERROR_MESSAGE);
        }
    }

    /**
     * Sequence filter computing the combined (OR) dimension profile of all sequences of a single
     * non-collection geometry. Each sequence is evaluated once, at its first coordinate.
     * <p>
     * Since 1.0.5 this filter implements OR semantics within one geometry: partially-NaN ordinate
     * patterns are treated as "the dimension is present, NaN values are preserved", aligning with
     * PostGIS which accepts NaN as a legal ordinate value.
     */
    public static class DimensionFilter implements CoordinateSequenceFilter {
        private boolean hasZ;
        private boolean hasM;
        private boolean hasSequence;

        public DimensionFilter() {
        }

        @Override
        public void filter(CoordinateSequence seq, int i) {
            if (i != 0) {
                // evaluate each sequence exactly once, at its first coordinate
                return;
            }
            hasSequence = true;
            int dimension = seq.getDimension();
            if (dimension < 2 || dimension > 4) {
                throw new IllegalArgumentException(String.format(UNSUPPORTED_DIMENSION_ERROR_TEMPLATE, dimension));
            }
            if (dimension == 2) {
                return;
            }
            if (dimension >= 4) {
                // 4-ordinate sequences are XYZM regardless of measures metadata
                hasZ = true;
                hasM = true;
                return;
            }
            int measures = seq.getMeasures();
            if (measures > 0) {
                // typed XYM sequence: metadata wins over values
                hasM = true;
                return;
            }
            // plain 3-ordinate sequence: Z presence is value-based (OR across coordinates)
            for (int k = 0; k < seq.size(); k++) {
                if (!Double.isNaN(seq.getOrdinate(k, 2))) {
                    hasZ = true;
                    break;
                }
            }
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public boolean isGeometryChanged() {
            return false;
        }

        public boolean hasZ() {
            return hasZ;
        }

        public boolean hasM() {
            return hasM;
        }

        public boolean hasSequence() {
            return hasSequence;
        }

        /**
         * Kept for backward compatibility; single-geometry profiles are never mixed
         * (collection strictness is handled by {@link #analyze(Geometry)}).
         */
        public boolean isMixed() {
            return false;
        }

        public boolean isEmpty() {
            return !hasSequence;
        }
    }
}
