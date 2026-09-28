package top.yunitytech.maven.jooq.binding;

import org.jetbrains.annotations.NotNull;
import org.jooq.*;
import org.jooq.conf.ParamType;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.WKTWriter;
import org.postgresql.util.PGobject;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;

/**
 * Abstract base jOOQ binding for PostgreSQL PostGIS spatial data types.
 * <p>
 * Handles bidirectional conversion between PostGIS spatial types (EWKB Hex, EWKT, WKT, byte[])
 * and JTS {@link Geometry} objects with full support for 2D (XY), 3D (XYZ), 3DM (XYM), and 4D (XYZM).
 *
 * @author gaoyunfeng
 */
public abstract class AbstractPostgisBinding implements Binding<Object, Geometry> {

    /**
     * Protected default constructor for subclasses.
     */
    protected AbstractPostgisBinding() {
    }

    /**
     * Standard GeometryFactory using CoordinateArraySequenceFactory.
     */
    public static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    /**
     * GeometryFactory supporting 4D coordinates (XYZM) for reading packed WKB sequences.
     */
    private static final GeometryFactory PACKED_GEOMETRY_FACTORY =
            new GeometryFactory(PackedCoordinateSequenceFactory.DOUBLE_FACTORY);

    /**
     * Returns the PostgreSQL spatial type name ("geometry" or "geography").
     *
     * @return spatial type name
     */
    public abstract String getSpatialTypeName();

    @Override
    @NotNull
    public Converter<Object, Geometry> converter() {
        return SpatialConverter.INSTANCE;
    }

    /**
     * High-tolerance spatial type converter.
     * Supports EWKB Hex, WKT, EWKT, PGobject, and binary byte[] WKB.
     */
    public static class SpatialConverter implements Converter<Object, Geometry> {

        /**
         * Singleton instance of the spatial converter.
         */
        public static final SpatialConverter INSTANCE = new SpatialConverter();

        /**
         * Default constructor.
         */
        public SpatialConverter() {
        }

        @Override
        public Geometry from(Object databaseObject) {
            if (databaseObject == null) {
                return null;
            }
            if (databaseObject instanceof Geometry) {
                Geometry geom = (Geometry) databaseObject;
                validateDimensionConsistency(geom);
                return geom;
            }
            try {
                if (databaseObject instanceof byte[]) {
                    return parseWkb((byte[]) databaseObject);
                }

                String text = null;
                if (databaseObject instanceof PGobject) {
                    PGobject pgObj = (PGobject) databaseObject;
                    text = pgObj.getValue();
                } else if (databaseObject instanceof String) {
                    text = (String) databaseObject;
                }

                if (text == null || text.trim().isEmpty()) {
                    return null;
                }
                text = text.trim();

                // 1. Detect Hex EWKB/WKB format (e.g. 01010000... or 00000000...)
                if (isHex(text)) {
                    return parseWkb(WKBReader.hexToBytes(text));
                }

                // 2. Detect PostGIS EWKT format (e.g. SRID=4326;POINT(...))
                if (text.regionMatches(true, 0, "SRID=", 0, 5)) {
                    int semicolon = text.indexOf(';');
                    if (semicolon > 5) {
                        int srid = Integer.parseInt(text.substring(5, semicolon).trim());
                        String wkt = fixWktEmptySpacing(text.substring(semicolon + 1).trim());
                        Geometry geom = new WKTReader(GEOMETRY_FACTORY).read(wkt);
                        geom.setSRID(srid);
                        validateDimensionConsistency(geom);
                        return geom;
                    }
                }

                // 3. Fallback to standard WKT format (e.g. POINT(1 2))
                Geometry geom = new WKTReader(GEOMETRY_FACTORY).read(fixWktEmptySpacing(text));
                validateDimensionConsistency(geom);
                return geom;

            } catch (ParseException e) {
                throw new RuntimeException("Error parsing spatial data from database object: " + databaseObject, e);
            }
        }

        private static Geometry parseWkb(byte[] bytes) throws ParseException {
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            if (bytes.length < 5) {
                Geometry g = new WKBReader(GEOMETRY_FACTORY).read(bytes);
                validateDimensionConsistency(g);
                return g;
            }
            boolean littleEndian = bytes[0] == 1;
            int type = littleEndian
                    ? (bytes[1] & 0xFF) | ((bytes[2] & 0xFF) << 8) | ((bytes[3] & 0xFF) << 16) | ((bytes[4] & 0xFF) << 24)
                    : ((bytes[1] & 0xFF) << 24) | ((bytes[2] & 0xFF) << 16) | ((bytes[3] & 0xFF) << 8) | (bytes[4] & 0xFF);
            boolean hasZ = (type & 0x80000000) != 0 || ((type & 0xFFFF) / 1000 == 1 || (type & 0xFFFF) / 1000 == 3);
            boolean hasM = (type & 0x40000000) != 0 || ((type & 0xFFFF) / 1000 == 2 || (type & 0xFFFF) / 1000 == 3);

            Geometry geom;
            if (!hasM) {
                // 2D (XY) or 3D (XYZ)
                geom = new WKBReader(GEOMETRY_FACTORY).read(bytes);
            } else if (!hasZ) {
                // 3DM (XYM): WKBReader reads M into the Z slot of standard Coordinate.
                // Remap coordinates to CoordinateXYM without losing empty components.
                Geometry raw = new WKBReader(GEOMETRY_FACTORY).read(bytes);
                geom = remapCoordinates(raw, GEOMETRY_FACTORY, true);
            } else {
                // 4D (XYZM): Read with PACKED_GEOMETRY_FACTORY and remap to CoordinateXYZM
                Geometry raw = new WKBReader(PACKED_GEOMETRY_FACTORY).read(bytes);
                geom = remapCoordinates(raw, GEOMETRY_FACTORY, false);
            }

            validateDimensionConsistency(geom);
            return geom;
        }

        @Override
        public Object to(Geometry userObject) {
            if (userObject == null) {
                return null;
            }
            return toSpatialRepresentation(userObject);
        }

        @Override
        @NotNull
        public Class<Object> fromType() {
            return Object.class;
        }

        @Override
        @NotNull
        public Class<Geometry> toType() {
            return Geometry.class;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof SpatialConverter;
        }

        @Override
        public int hashCode() {
            return SpatialConverter.class.hashCode();
        }
    }

    @Override
    public void sql(BindingSQLContext<Geometry> ctx) {
        String typeName = getSpatialTypeName();
        if (ctx.render().paramType() == ParamType.INLINED) {
            Geometry geom = ctx.value();
            if (geom == null) {
                ctx.render().sql("NULL::" + typeName);
            } else {
                ctx.render().sql("'" + toSpatialRepresentation(geom) + "'::" + typeName);
            }
        } else {
            ctx.render().sql("?::" + typeName);
        }
    }

    @Override
    @SuppressWarnings("try")
    public void register(BindingRegisterContext<Geometry> ctx) throws SQLException {
        ctx.statement().registerOutParameter(ctx.index(), Types.OTHER, getSpatialTypeName());
    }

    @Override
    @SuppressWarnings("try")
    public void set(BindingSetStatementContext<Geometry> ctx) throws SQLException {
        Geometry geom = ctx.value();
        if (geom == null) {
            ctx.statement().setNull(ctx.index(), Types.OTHER, getSpatialTypeName());
        } else {
            PGobject pgObj = new PGobject();
            pgObj.setType(getSpatialTypeName());
            pgObj.setValue(toSpatialRepresentation(geom));
            ctx.statement().setObject(ctx.index(), pgObj, Types.OTHER);
        }
    }

    @Override
    @SuppressWarnings("try")
    public void get(BindingGetResultSetContext<Geometry> ctx) throws SQLException {
        ctx.value(converter().from(ctx.resultSet().getObject(ctx.index())));
    }

    @Override
    @SuppressWarnings("try")
    public void get(BindingGetStatementContext<Geometry> ctx) throws SQLException {
        ctx.value(converter().from(ctx.statement().getObject(ctx.index())));
    }

    @Override
    public void set(BindingSetSQLOutputContext<Geometry> ctx) throws SQLException {
        throw new SQLFeatureNotSupportedException("SQLOutput not supported");
    }

    @Override
    public void get(BindingGetSQLInputContext<Geometry> ctx) throws SQLException {
        throw new SQLFeatureNotSupportedException("SQLInput not supported");
    }

    /**
     * Converts a JTS Geometry to an optimal representation for PostgreSQL:
     * <ul>
     *     <li>If the geometry has Measure (M) dimension (XYM or XYZM), outputs PostGIS EWKT (e.g. {@code SRID=4326;POINT ZM(1 2 3 4)}).</li>
     *     <li>Otherwise (XY or XYZ), outputs PostGIS EWKB Hex string.</li>
     * </ul>
     *
     * @param geom the geometry to serialize
     * @return spatial representation string
     * @throws IllegalArgumentException if the geometry has mixed coordinate dimensions
     */
    public static String toSpatialRepresentation(Geometry geom) {
        if (geom == null) {
            return null;
        }
        if (geom.isEmpty()) {
            WKBWriter writer = new WKBWriter(2, true);
            return WKBWriter.toHex(writer.write(geom));
        }

        DimensionFilter filter = new DimensionFilter();
        geom.apply(filter);

        if (filter.isMixed()) {
            throw new IllegalArgumentException(
                    "Mixed-dimension geometry is not supported: geometry components have inconsistent coordinate dimensions (e.g. XY mixed with XYZ/XYM/XYZM).");
        }

        boolean hasZ = filter.hasZ();
        boolean hasM = filter.hasM();

        if (hasM) {
            // JTS WKBWriter only supports 2D/3D (Z only). For M/ZM coordinates,
            // PostGIS natively parses EWKT format via geometry_in / geography_in.
            String wkt = fixWktEmptySpacing(new WKTWriter(4).write(geom));
            if (geom.getSRID() > 0) {
                return "SRID=" + geom.getSRID() + ";" + wkt;
            }
            return wkt;
        }

        // Standard 2D or 3D (Z) geometry -> EWKB Hex
        int dimension = hasZ ? 3 : 2;
        WKBWriter writer = new WKBWriter(dimension, true);
        return WKBWriter.toHex(writer.write(geom));
    }

    /**
     * Fixes JTS WKTWriter missing space before EMPTY for dimensioned types (e.g. 'MEMPTY' -> 'M EMPTY').
     *
     * @param wkt WKT string
     * @return normalized WKT string
     */
    public static String fixWktEmptySpacing(String wkt) {
        if (wkt == null || !wkt.contains("EMPTY")) {
            return wkt;
        }
        return wkt.replaceAll("(?i)(?<=\\b(?:ZM|Z|M))EMPTY\\b", " EMPTY");
    }

    /**
     * Validates that all non-empty coordinates within the geometry have consistent dimensions.
     *
     * @param geom geometry to validate
     * @throws IllegalArgumentException if geometry components have mixed coordinate dimensions
     */
    public static void validateDimensionConsistency(Geometry geom) {
        if (geom == null || geom.isEmpty()) {
            return;
        }
        DimensionFilter filter = new DimensionFilter();
        geom.apply(filter);
        if (filter.isMixed()) {
            throw new IllegalArgumentException(
                    "Mixed-dimension geometry is not supported: geometry components have inconsistent coordinate dimensions (e.g. XY mixed with XYZ/XYM/XYZM).");
        }
    }

    /**
     * Recursively remaps coordinate sequences of a geometry to CoordinateXYM or CoordinateXYZM,
     * preserving all geometry types, nested collections, and empty geometries.
     */
    private static Geometry remapCoordinates(Geometry g, GeometryFactory gf, boolean isXYM) {
        if (g == null) {
            return null;
        }
        int srid = g.getSRID();
        Geometry result;

        if (g.isEmpty()) {
            result = g.copy();
        } else if (g instanceof Point) {
            CoordinateSequence cs = remapSequence(((Point) g).getCoordinateSequence(), isXYM);
            result = gf.createPoint(cs);
        } else if (g instanceof LineString) {
            CoordinateSequence cs = remapSequence(((LineString) g).getCoordinateSequence(), isXYM);
            result = (g instanceof LinearRing) ? gf.createLinearRing(cs) : gf.createLineString(cs);
        } else if (g instanceof Polygon) {
            Polygon p = (Polygon) g;
            LinearRing shell = (LinearRing) remapCoordinates(p.getExteriorRing(), gf, isXYM);
            LinearRing[] holes = new LinearRing[p.getNumInteriorRing()];
            for (int i = 0; i < p.getNumInteriorRing(); i++) {
                holes[i] = (LinearRing) remapCoordinates(p.getInteriorRingN(i), gf, isXYM);
            }
            result = gf.createPolygon(shell, holes);
        } else if (g instanceof MultiPoint) {
            MultiPoint mp = (MultiPoint) g;
            Point[] points = new Point[mp.getNumGeometries()];
            for (int i = 0; i < mp.getNumGeometries(); i++) {
                points[i] = (Point) remapCoordinates(mp.getGeometryN(i), gf, isXYM);
            }
            result = gf.createMultiPoint(points);
        } else if (g instanceof MultiLineString) {
            MultiLineString mls = (MultiLineString) g;
            LineString[] lines = new LineString[mls.getNumGeometries()];
            for (int i = 0; i < mls.getNumGeometries(); i++) {
                lines[i] = (LineString) remapCoordinates(mls.getGeometryN(i), gf, isXYM);
            }
            result = gf.createMultiLineString(lines);
        } else if (g instanceof MultiPolygon) {
            MultiPolygon mp = (MultiPolygon) g;
            Polygon[] polys = new Polygon[mp.getNumGeometries()];
            for (int i = 0; i < mp.getNumGeometries(); i++) {
                polys[i] = (Polygon) remapCoordinates(mp.getGeometryN(i), gf, isXYM);
            }
            result = gf.createMultiPolygon(polys);
        } else if (g instanceof GeometryCollection) {
            GeometryCollection gc = (GeometryCollection) g;
            Geometry[] geoms = new Geometry[gc.getNumGeometries()];
            for (int i = 0; i < gc.getNumGeometries(); i++) {
                geoms[i] = remapCoordinates(gc.getGeometryN(i), gf, isXYM);
            }
            result = gf.createGeometryCollection(geoms);
        } else {
            throw new IllegalArgumentException("Unsupported geometry type: " + g.getClass().getName());
        }

        result.setSRID(srid);
        return result;
    }

    private static CoordinateSequence remapSequence(CoordinateSequence cs, boolean isXYM) {
        if (cs == null || cs.size() == 0) {
            return cs;
        }
        if (isXYM) {
            CoordinateXYM[] coords = new CoordinateXYM[cs.size()];
            for (int i = 0; i < cs.size(); i++) {
                coords[i] = new CoordinateXYM(cs.getX(i), cs.getY(i), cs.getZ(i));
            }
            return new CoordinateArraySequence(coords);
        } else {
            CoordinateXYZM[] coords = new CoordinateXYZM[cs.size()];
            for (int i = 0; i < cs.size(); i++) {
                coords[i] = new CoordinateXYZM(cs.getX(i), cs.getY(i), cs.getZ(i), cs.getM(i));
            }
            return new CoordinateArraySequence(coords);
        }
    }

    /**
     * Filter to verify all non-empty coordinates within a geometry have consistent dimensions (XY / XYZ / XYM / XYZM).
     */
    public static class DimensionFilter implements CoordinateSequenceFilter {
        private Boolean hasZ = null;
        private Boolean hasM = null;
        private boolean mixed = false;

        /**
         * Default constructor.
         */
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

        /**
         * Returns true if coordinates have mixed dimensions.
         *
         * @return true if mixed
         */
        public boolean isMixed() {
            return mixed;
        }

        /**
         * Returns true if coordinates have a valid Z dimension.
         *
         * @return true if has Z
         */
        public boolean hasZ() {
            return hasZ != null && hasZ;
        }

        /**
         * Returns true if coordinates have a valid M dimension.
         *
         * @return true if has M
         */
        public boolean hasM() {
            return hasM != null && hasM;
        }

        /**
         * Returns true if no coordinates were encountered.
         *
         * @return true if empty
         */
        public boolean isEmpty() {
            return hasZ == null;
        }
    }

    private static boolean isHex(String s) {
        if (s == null || s.length() < 2 || (s.length() % 2 != 0)) {
            return false;
        }
        // PostGIS EWKB always starts with byte order 00 (big-endian) or 01 (little-endian)
        char c0 = s.charAt(0);
        char c1 = s.charAt(1);
        if (c0 != '0' || (c1 != '0' && c1 != '1')) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean isHexChar = (c >= '0' && c <= '9') ||
                    (c >= 'a' && c <= 'f') ||
                    (c >= 'A' && c <= 'F');
            if (!isHexChar) {
                return false;
            }
        }
        return true;
    }
}
