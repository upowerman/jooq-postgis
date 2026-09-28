package top.yunitytech.maven.jooq.binding;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.postgresql.util.PGobject;
import top.yunitytech.maven.jooq.binding.internal.DimensionAnalyzer;
import top.yunitytech.maven.jooq.binding.internal.SpatialWkbPool;

/**
 * Dedicated spatial codec for PostgreSQL / PostGIS and JTS {@link Geometry}.
 * <p>
 * Handles bidirectional serialization and deserialization between database spatial formats
 * (EWKB Hex, PostGIS EWKT, standard WKT, byte[] WKB, {@link PGobject}) and JTS Geometry,
 * supporting 2D (XY), 3D (XYZ), 3DM (XYM), and 4D (XYZM) coordinate dimensions.
 *
 * @author gaoyunfeng
 */
public final class PostgisCodec {

    /**
     * Standard GeometryFactory using CoordinateArraySequenceFactory.
     */
    public static final GeometryFactory GEOMETRY_FACTORY = SpatialWkbPool.GEOMETRY_FACTORY;

    /**
     * GeometryFactory supporting 4D coordinates (XYZM) for reading packed WKB sequences.
     */
    public static final GeometryFactory PACKED_GEOMETRY_FACTORY = SpatialWkbPool.PACKED_GEOMETRY_FACTORY;

    private PostgisCodec() {
        // Private constructor for static utility class
    }

    /**
     * Deserializes a database object into a JTS {@link Geometry}.
     * Supports {@link PGobject}, {@code byte[]}, Hex EWKB String, PostGIS EWKT String, standard WKT String, or existing Geometry.
     *
     * @param databaseObject raw object returned from JDBC/database
     * @return deserialized JTS Geometry, or {@code null} if databaseObject is null or blank
     * @throws RuntimeException if parsing fails
     */
    public static Geometry from(Object databaseObject) {
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
                return fromWkb((byte[]) databaseObject);
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
                return fromWkb(WKBReader.hexToBytes(text));
            }

            // 2. Fallback to text parsing (EWKT or WKT)
            return fromWkt(text);

        } catch (ParseException e) {
            throw new RuntimeException("Error parsing spatial data from database object: " + databaseObject, e);
        }
    }

    /**
     * Deserializes binary WKB/EWKB bytes into a JTS {@link Geometry}.
     * Preserves SRID and handles XY, XYZ, XYM, and XYZM coordinate dimensions.
     *
     * @param bytes binary WKB or EWKB data
     * @return deserialized JTS Geometry, or {@code null} if bytes is null or empty
     * @throws ParseException if binary data is corrupted
     * @throws IllegalArgumentException if geometry components have mixed coordinate dimensions
     */
    public static Geometry fromWkb(byte[] bytes) throws ParseException {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        if (bytes.length < 5) {
            Geometry g = SpatialWkbPool.getWkbReader().read(bytes);
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
            geom = SpatialWkbPool.getWkbReader().read(bytes);
        } else if (!hasZ) {
            // 3DM (XYM): WKBReader reads M into the Z slot of standard Coordinate.
            // Remap coordinates to CoordinateXYM without losing empty components.
            Geometry raw = SpatialWkbPool.getWkbReader().read(bytes);
            geom = remapCoordinates(raw, GEOMETRY_FACTORY, true);
        } else {
            // 4D (XYZM): Read with PACKED_GEOMETRY_FACTORY and remap to CoordinateXYZM
            Geometry raw = SpatialWkbPool.getPackedWkbReader().read(bytes);
            geom = remapCoordinates(raw, GEOMETRY_FACTORY, false);
        }

        validateDimensionConsistency(geom);
        return geom;
    }

    /**
     * Deserializes WKT or PostGIS EWKT text into a JTS {@link Geometry}.
     *
     * @param text WKT or EWKT text (e.g. {@code "SRID=4326;POINT(1 2)"} or {@code "POINT(1 2)"})
     * @return deserialized JTS Geometry
     * @throws ParseException if WKT format is invalid
     * @throws IllegalArgumentException if geometry components have mixed coordinate dimensions
     */
    public static Geometry fromWkt(String text) throws ParseException {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        text = text.trim();

        // Detect PostGIS EWKT format (e.g. SRID=4326;POINT(...))
        if (text.regionMatches(true, 0, "SRID=", 0, 5)) {
            int semicolon = text.indexOf(';');
            if (semicolon > 5) {
                int srid = Integer.parseInt(text.substring(5, semicolon).trim());
                String wkt = fixWktEmptySpacing(text.substring(semicolon + 1).trim());
                Geometry geom = SpatialWkbPool.getWktReader().read(wkt);
                geom.setSRID(srid);
                validateDimensionConsistency(geom);
                return geom;
            }
        }

        // Standard WKT format (e.g. POINT(1 2))
        Geometry geom = SpatialWkbPool.getWktReader().read(fixWktEmptySpacing(text));
        validateDimensionConsistency(geom);
        return geom;
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
            WKBWriter writer = SpatialWkbPool.getWkbWriter(2);
            return WKBWriter.toHex(writer.write(geom));
        }

        DimensionAnalyzer.Result result = DimensionAnalyzer.analyze(geom);
        if (result.isMixed()) {
            DimensionAnalyzer.validateDimensionConsistency(geom);
        }

        if (result.hasM()) {
            // JTS WKBWriter only supports 2D/3D (Z only). For M/ZM coordinates,
            // PostGIS natively parses EWKT format via geometry_in / geography_in.
            String wkt = fixWktEmptySpacing(SpatialWkbPool.getWktWriter4D().write(geom));
            if (geom.getSRID() > 0) {
                return "SRID=" + geom.getSRID() + ";" + wkt;
            }
            return wkt;
        }

        // Standard 2D or 3D (Z) geometry -> EWKB Hex
        WKBWriter writer = SpatialWkbPool.getWkbWriter(result.getWkbOutputDimension());
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
        DimensionAnalyzer.validateDimensionConsistency(geom);
    }

    /**
     * Checks if a string is a valid hexadecimal EWKB representation.
     *
     * @param s candidate string
     * @return true if string is even-length hex starting with 00 or 01
     */
    public static boolean isHex(String s) {
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
     *
     * @deprecated Kept for backward compatibility. Use {@link DimensionAnalyzer} instead.
     */
    @Deprecated
    public static class DimensionFilter extends DimensionAnalyzer.DimensionFilter {
        public DimensionFilter() {
            super();
        }
    }
}
