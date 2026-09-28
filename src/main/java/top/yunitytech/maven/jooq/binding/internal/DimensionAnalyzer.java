package top.yunitytech.maven.jooq.binding.internal;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

import java.io.Serializable;
import java.util.Objects;

/**
 * Robust spatial coordinate dimension detector and validator.
 * <p>
 * Replaces the fragile, allocation-heavy DimensionFilter with an optimized analyzer
 * supporting 2D (XY), 3D (XYZ), 3DM (XYM), 4D (XYZM), EMPTY, and MIXED coordinate dimensions.
 *
 * @author gaoyunfeng
 */
public final class DimensionAnalyzer {

    private static final String MIXED_DIMENSION_ERROR_MESSAGE =
            "Mixed-dimension geometry is not supported: geometry components have inconsistent coordinate dimensions (e.g. XY mixed with XYZ/XYM/XYZM).";

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
     */
    public static Result analyze(Geometry geom) {
        if (geom == null || geom.isEmpty()) {
            return Result.EMPTY;
        }

        // Fast-path for single point
        if (geom instanceof Point) {
            Coordinate c = geom.getCoordinate();
            if (c == null) {
                return Result.EMPTY;
            }
            boolean hasZ = !Double.isNaN(c.getZ());
            boolean hasM = !Double.isNaN(c.getM());
            return Result.of(hasZ, hasM);
        }

        // Deep inspection across sequences
        DimensionFilter filter = new DimensionFilter();
        geom.apply(filter);

        if (filter.isMixed()) {
            return Result.MIXED;
        }
        if (filter.isEmpty()) {
            return Result.EMPTY;
        }
        return Result.of(filter.hasZ(), filter.hasM());
    }

    /**
     * Validates that all coordinates within the geometry have consistent dimensions.
     *
     * @param geom geometry to validate
     * @throws IllegalArgumentException if geometry components have mixed coordinate dimensions
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
     * Internal sequence filter used for deep traversal across complex/nested geometries.
     */
    public static class DimensionFilter implements CoordinateSequenceFilter {
        private Boolean hasZ = null;
        private Boolean hasM = null;
        private boolean mixed = false;

        public DimensionFilter() {
        }

        @Override
        public void filter(CoordinateSequence seq, int i) {
            if (mixed || seq.size() == 0) {
                return;
            }
            Coordinate c = seq.getCoordinate(i);
            boolean z = !Double.isNaN(c.getZ());
            boolean m = !Double.isNaN(c.getM());
            if (hasZ == null) {
                hasZ = z;
                hasM = m;
            } else if (hasZ != z || hasM != m) {
                mixed = true;
            }
        }

        @Override
        public boolean isDone() {
            return mixed;
        }

        @Override
        public boolean isGeometryChanged() {
            return false;
        }

        public boolean isMixed() {
            return mixed;
        }

        public boolean hasZ() {
            return hasZ != null && hasZ;
        }

        public boolean hasM() {
            return hasM != null && hasM;
        }

        public boolean isEmpty() {
            return hasZ == null;
        }
    }
}
