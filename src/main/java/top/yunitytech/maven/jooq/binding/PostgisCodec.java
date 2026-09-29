package top.yunitytech.maven.jooq.binding;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.postgresql.util.PGobject;
import top.yunitytech.maven.jooq.binding.internal.DimensionAnalyzer;
import top.yunitytech.maven.jooq.binding.internal.SpatialWkbPool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Dedicated spatial codec for PostgreSQL / PostGIS and JTS {@link Geometry}.
 * <p>
 * Handles bidirectional serialization and deserialization between database spatial formats
 * (EWKB Hex, PostGIS EWKT, standard WKT, byte[] WKB, {@link PGobject}) and JTS Geometry,
 * supporting 2D (XY), 3D (XYZ), 3DM (XYM), and 4D (XYZM) coordinate dimensions.
 * <p>
 * Dimension handling (since 1.0.5):
 * <ul>
 *     <li><b>Reading WKB/EWKB:</b> the coordinate dimension is derived from the type flags of every
 *     leaf geometry in the byte stream (not just the outermost header), so WKB produced by
 *     non-PostGIS tools whose collection headers under-declare dimension flags is still decoded
 *     correctly instead of silently re-interpreting M values as Z. Leaf flags must be uniform —
 *     dimension-heterogeneous input is rejected exactly like PostGIS rejects it.</li>
 *     <li><b>NaN ordinates:</b> NaN is a legal PostGIS ordinate value, not a dimension signal.
 *     Geometries read from or written to the database preserve NaN values verbatim; a Z (or M)
 *     dimension is considered present when any coordinate carries a non-NaN value, or when the
 *     sequence/flags declare it. See {@link DimensionAnalyzer} for the full rules.</li>
 *     <li><b>Writing:</b> all dimensions serialize as big-endian EWKB Hex. M/ZM geometries are
 *     written by a built-in EWKB writer because JTS {@link WKBWriter} cannot emit the M flag
 *     (and JTS {@code WKTWriter} silently drops the M marker when all M values are NaN).</li>
 *     <li><b>Empty geometries:</b> JTS cannot represent typed empties ("POINT Z EMPTY") — an
 *     empty geometry carries no dimension information, so empties serialize as 2D and PostgreSQL
 *     rejects them for Z/M/ZM-typmod columns ("Column has Z dimension but geometry does not").
 *     When the column type is known, use {@link #toSpatialRepresentation(Geometry,
 *     DimensionAnalyzer.CoordinateDimension)} to emit a dimensioned empty. Reading is unaffected:
 *     dimensioned empties written by PostGIS decode to empty JTS geometries.</li>
 * </ul>
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

    private static final String MIXED_DIMENSION_ERROR_MESSAGE =
            "Mixed-dimension geometry is not supported: geometry components have inconsistent coordinate dimensions (e.g. XY mixed with XYZ/XYM/XYZM).";

    private static final int MAX_EXCEPTION_SNIPPET_LENGTH = 64;

    private static final int MAX_SRID_TOKEN_LENGTH = 32;

    private PostgisCodec() {
        // Private constructor for static utility class
    }

    /**
     * Deserializes a database object into a JTS {@link Geometry}.
     * Supports {@link PGobject}, {@code byte[]}, Hex EWKB String, PostGIS EWKT String, standard WKT String, or existing Geometry.
     *
     * @param databaseObject raw object returned from JDBC/database
     * @return deserialized JTS Geometry, or {@code null} if databaseObject is null or blank
     * @throws RuntimeException     if parsing fails
     * @throws IllegalArgumentException if the input declares inconsistent coordinate dimensions
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
            throw new RuntimeException("Error parsing spatial data from database object (" + describe(databaseObject) + "): " + e.getMessage(), e);
        }
    }

    /**
     * Deserializes binary WKB/EWKB bytes into a JTS {@link Geometry}.
     * <p>
     * The coordinate dimension is determined by scanning the type flags of <em>every leaf
     * geometry</em> in the stream (JTS reads each element by its own flags, and non-PostGIS
     * producers sometimes write collection headers without dimension flags). All leaf flags
     * must agree; dimension-heterogeneous input is rejected.
     *
     * @param bytes binary WKB or EWKB data (little- or big-endian, EWKB or ISO WKB type codes)
     * @return deserialized JTS Geometry, or {@code null} if bytes is null or empty
     * @throws ParseException            if binary data is corrupted
     * @throws IllegalArgumentException  if leaf geometries declare inconsistent dimensions,
     *                                   or the stream is malformed / uses an unsupported geometry type
     */
    public static Geometry fromWkb(byte[] bytes) throws ParseException {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        WkbDimensionProfile profile = WkbScanner.scan(bytes);

        Geometry geom;
        if (!profile.hasM) {
            // 2D (XY) or 3D (XYZ)
            geom = SpatialWkbPool.getWkbReader().read(bytes);
        } else if (!profile.hasZ) {
            // 3DM (XYM): JTS WKBReader reads the M value into ordinate slot 2
            // (the Z slot of the standard Coordinate); remap to CoordinateXYM.
            Geometry raw = SpatialWkbPool.getWkbReader().read(bytes);
            geom = remapCoordinates(raw, GEOMETRY_FACTORY, false, true);
        } else {
            // 4D (XYZM): read with the packed factory (measures metadata preserved) and remap
            Geometry raw = SpatialWkbPool.getPackedWkbReader().read(bytes);
            geom = remapCoordinates(raw, GEOMETRY_FACTORY, true, true);
        }

        validateDimensionConsistency(geom);
        return geom;
    }

    /**
     * Deserializes WKT or PostGIS EWKT text into a JTS {@link Geometry}.
     *
     * @param text WKT or EWKT text (e.g. {@code "SRID=4326;POINT(1 2)"} or {@code "POINT(1 2)"})
     * @return deserialized JTS Geometry
     * @throws ParseException           if WKT format is invalid
     * @throws IllegalArgumentException if geometry components have inconsistent coordinate dimensions
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
                String sridToken = text.substring(5, semicolon).trim();
                int srid;
                try {
                    srid = Integer.parseInt(sridToken);
                } catch (NumberFormatException e) {
                    String snippet = sridToken.length() <= MAX_SRID_TOKEN_LENGTH
                            ? sridToken
                            : sridToken.substring(0, MAX_SRID_TOKEN_LENGTH) + "...";
                    throw new ParseException("Invalid SRID in EWKT: '" + snippet + "'");
                }
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
     * Converts a JTS Geometry to its PostgreSQL representation: big-endian EWKB Hex string,
     * for all coordinate dimensions (XY, XYZ, XYM, XYZM). SRID is embedded when non-zero.
     * NaN ordinate values are preserved bit-exactly.
     *
     * @param geom the geometry to serialize
     * @return hex EWKB string
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
        validateSridConsistency(geom);

        if (result.hasM()) {
            // JTS WKBWriter cannot emit the M flag, and WKTWriter(4) silently drops the M marker
            // when all M values are NaN — write dimensioned EWKB ourselves instead.
            return WKBWriter.toHex(writeEwkb(geom, result.hasZ(), true));
        }

        // Standard 2D or 3D (Z) geometry -> EWKB Hex via JTS
        WKBWriter writer = SpatialWkbPool.getWkbWriter(result.getWkbOutputDimension());
        return WKBWriter.toHex(writer.write(geom));
    }

    /**
     * Converts a JTS Geometry to its PostgreSQL representation, declaring an explicit coordinate
     * dimension for EMPTY geometries.
     * <p>
     * JTS cannot represent typed empties ("POINT Z EMPTY"): an empty geometry carries no dimension
     * information, and {@link #toSpatialRepresentation(Geometry)} therefore serializes all empties
     * as 2D — which PostgreSQL rejects for Z/M/ZM-typmod columns
     * ("Column has Z dimension but geometry does not"). When the target column type is known,
     * pass its dimension here to emit a dimensioned empty, e.g.
     * {@code toSpatialRepresentation(emptyPoint, CoordinateDimension.XYZ)} produces "POINT Z EMPTY".
     * <p>
     * For non-empty geometries the dimension is always derived from the coordinate data; the
     * requested dimension must agree with the data, otherwise an {@link IllegalArgumentException}
     * is thrown.
     *
     * @param geom      the geometry to serialize (may be empty)
     * @param dimension the coordinate dimension to declare for empty geometries (XY / XYZ / XYM / XYZM)
     * @return hex EWKB string
     * @throws IllegalArgumentException if {@code dimension} is {@code null}, EMPTY or MIXED; if a
     *                                  non-empty geometry's data-derived dimension disagrees with
     *                                  the requested dimension; or if the geometry has mixed
     *                                  dimensions or inconsistent component SRIDs
     */
    public static String toSpatialRepresentation(Geometry geom, DimensionAnalyzer.CoordinateDimension dimension) {
        if (dimension == null || dimension == DimensionAnalyzer.CoordinateDimension.EMPTY
                || dimension == DimensionAnalyzer.CoordinateDimension.MIXED) {
            throw new IllegalArgumentException(
                    "dimension must be one of XY, XYZ, XYM, XYZM (an explicit dimension is only meaningful for EMPTY geometries)");
        }
        if (geom == null) {
            return null;
        }
        boolean requestedZ = dimension == DimensionAnalyzer.CoordinateDimension.XYZ
                || dimension == DimensionAnalyzer.CoordinateDimension.XYZM;
        boolean requestedM = dimension == DimensionAnalyzer.CoordinateDimension.XYM
                || dimension == DimensionAnalyzer.CoordinateDimension.XYZM;
        if (geom.isEmpty()) {
            if (!requestedZ && !requestedM) {
                return toSpatialRepresentation(geom);
            }
            return WKBWriter.toHex(writeEwkb(geom, requestedZ, requestedM));
        }
        DimensionAnalyzer.Result actual = DimensionAnalyzer.analyze(geom);
        if (actual.isMixed()) {
            DimensionAnalyzer.validateDimensionConsistency(geom);
        }
        if (actual.hasZ() != requestedZ || actual.hasM() != requestedM) {
            throw new IllegalArgumentException(
                    "Geometry resolves to " + actual.getDimension() + " but " + dimension
                            + " was requested: for non-empty geometries the explicit dimension must match the coordinate data.");
        }
        return toSpatialRepresentation(geom);
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
     * Validates that all geometry components resolve to a consistent coordinate dimension.
     *
     * @param geom geometry to validate
     * @throws IllegalArgumentException if geometry components have inconsistent coordinate dimensions
     */
    public static void validateDimensionConsistency(Geometry geom) {
        DimensionAnalyzer.validateDimensionConsistency(geom);
    }

    /**
     * Ensures non-empty collection components do not carry a non-zero SRID that differs from the
     * root geometry. EWKB embeds only the root SRID, so conflicting component SRIDs would be
     * silently lost on write; PostGIS likewise requires uniform SRIDs within a collection.
     * Components with SRID 0 are considered "unset" and inherit the root's SRID.
     *
     * @param geom geometry to validate (leaf geometries are trivially consistent)
     * @throws IllegalArgumentException if a non-empty collection component declares a non-zero
     *                                  SRID different from the root geometry's SRID
     */
    private static void validateSridConsistency(Geometry geom) {
        if (geom instanceof GeometryCollection) {
            validateCollectionSrids((GeometryCollection) geom, geom.getSRID());
        }
    }

    private static void validateCollectionSrids(GeometryCollection collection, int rootSrid) {
        for (int i = 0; i < collection.getNumGeometries(); i++) {
            Geometry child = collection.getGeometryN(i);
            if (child.isEmpty()) {
                // empty components carry no coordinates; their SRID is irrelevant
                continue;
            }
            int childSrid = child.getSRID();
            if (childSrid != 0 && childSrid != rootSrid) {
                throw new IllegalArgumentException(
                        "Inconsistent SRID in geometry collection: component declares SRID " + childSrid
                                + " but the root geometry declares SRID " + rootSrid
                                + ". Only the root SRID is embedded in EWKB; PostGIS requires uniform SRIDs within a collection.");
            }
            if (child instanceof GeometryCollection) {
                validateCollectionSrids((GeometryCollection) child, rootSrid);
            }
        }
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

    // =========================================================================
    // WKB dimension scanning
    // =========================================================================

    /** Immutable leaf-flag dimension profile of a WKB byte stream. */
    private static final class WkbDimensionProfile {
        final boolean hasZ;
        final boolean hasM;

        WkbDimensionProfile(boolean hasZ, boolean hasM) {
            this.hasZ = hasZ;
            this.hasM = hasM;
        }
    }

    /**
     * Walks the type-flag headers of a WKB/EWKB stream (coordinate payloads are skipped without
     * being decoded) and computes the uniform dimension profile declared by all leaf geometries.
     * Collection/multi headers are ignored for profiling — only leaves count — because ISO WKB
     * allows collection headers to under-declare the dimension of their children, and JTS itself
     * parses every element by its own flags.
     */
    private static final class WkbScanner {
        private static final int TYPE_POINT = 1;
        private static final int TYPE_LINESTRING = 2;
        private static final int TYPE_POLYGON = 3;
        private static final int TYPE_MULTIPOINT = 4;
        private static final int TYPE_MULTILINESTRING = 5;
        private static final int TYPE_MULTIPOLYGON = 6;
        private static final int TYPE_GEOMETRYCOLLECTION = 7;

        private final byte[] bytes;
        private int pos;
        private boolean hasZ;
        private boolean hasM;
        private boolean seenLeaf;

        private WkbScanner(byte[] bytes) {
            this.bytes = bytes;
        }

        static WkbDimensionProfile scan(byte[] bytes) {
            WkbScanner scanner = new WkbScanner(bytes);
            scanner.scanGeometry();
            if (scanner.pos != bytes.length) {
                throw new IllegalArgumentException(malformed(
                        "trailing bytes after geometry (" + (bytes.length - scanner.pos) + " unexpected)"));
            }
            return new WkbDimensionProfile(scanner.hasZ, scanner.hasM);
        }

        private void scanGeometry() {
            expect(5);
            int byteOrder = bytes[pos] & 0xFF;
            if (byteOrder != 0 && byteOrder != 1) {
                throw new IllegalArgumentException(malformed("invalid byte order marker " + byteOrder));
            }
            long type = uint32(pos + 1, byteOrder);
            pos += 5;

            int geometryType = (int) ((type & 0xFFFFL) % 1000);
            long isoDimension = (type & 0xFFFFL) / 1000;
            boolean z = (type & 0x80000000L) != 0 || isoDimension == 1 || isoDimension == 3;
            boolean m = (type & 0x40000000L) != 0 || isoDimension == 2 || isoDimension == 3;
            if ((type & 0x20000000L) != 0) {
                // EWKB SRID flag: 4 bytes follow the type
                expect(4);
                pos += 4;
            }
            int dimension = 2 + (z ? 1 : 0) + (m ? 1 : 0);

            switch (geometryType) {
                case TYPE_POINT:
                    registerLeaf(z, m);
                    expect(8L * dimension);
                    pos += 8 * dimension;
                    break;
                case TYPE_LINESTRING:
                    registerLeaf(z, m);
                    skipCoordinateArray(byteOrder, dimension);
                    break;
                case TYPE_POLYGON:
                    registerLeaf(z, m);
                    int ringCount = int32(byteOrder);
                    if (ringCount < 0) {
                        throw new IllegalArgumentException(malformed("negative ring count " + ringCount));
                    }
                    for (int i = 0; i < ringCount; i++) {
                        skipCoordinateArray(byteOrder, dimension);
                    }
                    break;
                case TYPE_MULTIPOINT:
                case TYPE_MULTILINESTRING:
                case TYPE_MULTIPOLYGON:
                case TYPE_GEOMETRYCOLLECTION:
                    int childCount = int32(byteOrder);
                    if (childCount < 0) {
                        throw new IllegalArgumentException(malformed("negative child count " + childCount));
                    }
                    for (int i = 0; i < childCount; i++) {
                        scanGeometry();
                    }
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Unsupported WKB geometry type code: " + geometryType);
            }
        }

        private void registerLeaf(boolean z, boolean m) {
            if (seenLeaf && (z != hasZ || m != hasM)) {
                throw new IllegalArgumentException(MIXED_DIMENSION_ERROR_MESSAGE);
            }
            seenLeaf = true;
            hasZ = z;
            hasM = m;
        }

        private void skipCoordinateArray(int byteOrder, int dimension) {
            long count = int32(byteOrder) & 0xFFFFFFFFL;
            expect(count * 8L * dimension);
            pos += (int) (count * 8L * dimension);
        }

        private long uint32(int offset, int byteOrder) {
            expectAt(offset, 4);
            if (byteOrder == 1) {
                return (bytes[offset] & 0xFFL)
                        | ((bytes[offset + 1] & 0xFFL) << 8)
                        | ((bytes[offset + 2] & 0xFFL) << 16)
                        | ((bytes[offset + 3] & 0xFFL) << 24);
            }
            return ((bytes[offset] & 0xFFL) << 24)
                    | ((bytes[offset + 1] & 0xFFL) << 16)
                    | ((bytes[offset + 2] & 0xFFL) << 8)
                    | (bytes[offset + 3] & 0xFFL);
        }

        private int int32(int byteOrder) {
            expect(4);
            int value = (int) uint32(pos, byteOrder);
            pos += 4;
            return value;
        }

        private void expect(long needed) {
            expectAt(pos, needed);
        }

        private void expectAt(int offset, long needed) {
            if (offset < 0 || needed < 0 || bytes.length - offset < needed) {
                throw new IllegalArgumentException(malformed("truncated stream"));
            }
        }

        private static String malformed(String reason) {
            return "Malformed WKB/EWKB input: " + reason;
        }
    }

    // =========================================================================
    // Dimensioned EWKB writing (XYM / XYZM)
    // =========================================================================

    /**
     * Writes big-endian EWKB with explicit Z/M flags for geometries whose dimension profile
     * involves M. Coordinates with fewer ordinates than the target profile are padded with NaN.
     * The SRID is embedded at the root geometry only, matching JTS {@link WKBWriter} behaviour.
     */
    private static byte[] writeEwkb(Geometry geom, boolean hasZ, boolean hasM) {
        int size = ewkbSize(geom, hasZ, hasM, true);
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        writeEwkbGeometry(geom, hasZ, hasM, buffer, true);
        if (buffer.position() != size) {
            throw new IllegalStateException("EWKB size pre-computation mismatch");
        }
        return buffer.array();
    }

    private static void writeEwkbGeometry(Geometry geom, boolean hasZ, boolean hasM, ByteBuffer buffer, boolean root) {
        int geometryType = ewkbGeometryType(geom);
        int flags = geometryType
                | (hasZ ? 0x80000000 : 0)
                | (hasM ? 0x40000000 : 0)
                | (root && geom.getSRID() != 0 ? 0x20000000 : 0);
        buffer.put((byte) 0); // big-endian marker
        buffer.putInt(flags);
        if (root && geom.getSRID() != 0) {
            buffer.putInt(geom.getSRID());
        }

        if (geom instanceof Point) {
            CoordinateSequence cs = ((Point) geom).getCoordinateSequence();
            int n = cs == null ? 0 : cs.size();
            if (n == 0) {
                // EMPTY points still carry one (NaN-filled) coordinate slot in WKB
                writeEwkbCoordinate(buffer, null, 0, hasZ, hasM);
            } else {
                for (int i = 0; i < n; i++) {
                    writeEwkbCoordinate(buffer, cs, i, hasZ, hasM);
                }
            }
        } else if (geom instanceof LineString) {
            CoordinateSequence cs = ((LineString) geom).getCoordinateSequence();
            buffer.putInt(cs.size());
            for (int i = 0; i < cs.size(); i++) {
                writeEwkbCoordinate(buffer, cs, i, hasZ, hasM);
            }
        } else if (geom instanceof Polygon) {
            Polygon polygon = (Polygon) geom;
            if (polygon.isEmpty()) {
                // empty polygon has no exterior ring (JTS returns null)
                buffer.putInt(0);
                return;
            }
            buffer.putInt(1 + polygon.getNumInteriorRing());
            writeEwkbRing(polygon.getExteriorRing(), hasZ, hasM, buffer);
            for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
                writeEwkbRing(polygon.getInteriorRingN(i), hasZ, hasM, buffer);
            }
        } else if (geom instanceof MultiPoint) {
            writeEwkbChildren(((MultiPoint) geom), hasZ, hasM, buffer);
        } else if (geom instanceof MultiLineString) {
            writeEwkbChildren(((MultiLineString) geom), hasZ, hasM, buffer);
        } else if (geom instanceof MultiPolygon) {
            writeEwkbChildren(((MultiPolygon) geom), hasZ, hasM, buffer);
        } else if (geom instanceof GeometryCollection) {
            writeEwkbChildren((GeometryCollection) geom, hasZ, hasM, buffer);
        } else {
            throw new IllegalArgumentException("Unsupported geometry type: " + geom.getClass().getName());
        }
    }

    private static void writeEwkbChildren(GeometryCollection collection, boolean hasZ, boolean hasM, ByteBuffer buffer) {
        buffer.putInt(collection.getNumGeometries());
        for (int i = 0; i < collection.getNumGeometries(); i++) {
            writeEwkbGeometry(collection.getGeometryN(i), hasZ, hasM, buffer, false);
        }
    }

    private static void writeEwkbRing(LineString ring, boolean hasZ, boolean hasM, ByteBuffer buffer) {
        CoordinateSequence cs = ring.getCoordinateSequence();
        buffer.putInt(cs.size());
        for (int i = 0; i < cs.size(); i++) {
            writeEwkbCoordinate(buffer, cs, i, hasZ, hasM);
        }
    }

    private static void writeEwkbCoordinate(ByteBuffer buffer, CoordinateSequence cs, int index, boolean hasZ, boolean hasM) {
        buffer.putDouble(ordinate(cs, index, 0));
        buffer.putDouble(ordinate(cs, index, 1));
        if (hasZ) {
            buffer.putDouble(ordinate(cs, index, 2));
        }
        if (hasM) {
            // M is always the last ordinate when present (x, y, [z], m)
            buffer.putDouble(cs == null ? Double.NaN : ordinate(cs, index, cs.getDimension() - 1));
        }
    }

    private static double ordinate(CoordinateSequence cs, int index, int ordinateIndex) {
        if (cs == null || ordinateIndex < 0 || ordinateIndex >= cs.getDimension()) {
            return Double.NaN;
        }
        return cs.getOrdinate(index, ordinateIndex);
    }

    private static int ewkbGeometryType(Geometry geom) {
        if (geom instanceof Point) return 1;
        if (geom instanceof LineString) return 2; // includes LinearRing inside polygons
        if (geom instanceof Polygon) return 3;
        if (geom instanceof MultiPoint) return 4;
        if (geom instanceof MultiLineString) return 5;
        if (geom instanceof MultiPolygon) return 6;
        if (geom instanceof GeometryCollection) return 7;
        throw new IllegalArgumentException("Unsupported geometry type: " + geom.getClass().getName());
    }

    private static int ewkbSize(Geometry geom, boolean hasZ, boolean hasM, boolean root) {
        int dimension = 2 + (hasZ ? 1 : 0) + (hasM ? 1 : 0);
        long coordinateSize = 8L * dimension;
        int self = 5 + (root && geom.getSRID() != 0 ? 4 : 0);

        if (geom instanceof Point) {
            return (int) (self + coordinateSize);
        }
        if (geom instanceof LineString) {
            CoordinateSequence cs = ((LineString) geom).getCoordinateSequence();
            return (int) (self + 4 + cs.size() * coordinateSize);
        }
        if (geom instanceof Polygon) {
            Polygon polygon = (Polygon) geom;
            if (polygon.isEmpty()) {
                return self + 4;
            }
            long total = self + 4;
            total += 4 + polygon.getExteriorRing().getCoordinateSequence().size() * coordinateSize;
            for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
                total += 4 + polygon.getInteriorRingN(i).getCoordinateSequence().size() * coordinateSize;
            }
            return (int) total;
        }
        if (geom instanceof MultiPoint || geom instanceof MultiLineString
                || geom instanceof MultiPolygon || geom instanceof GeometryCollection) {
            GeometryCollection collection = (GeometryCollection) geom;
            long total = self + 4;
            for (int i = 0; i < collection.getNumGeometries(); i++) {
                total += ewkbSize(collection.getGeometryN(i), hasZ, hasM, false);
            }
            return (int) total;
        }
        throw new IllegalArgumentException("Unsupported geometry type: " + geom.getClass().getName());
    }

    // =========================================================================
    // Coordinate remapping (WKB read normalization)
    // =========================================================================

    /**
     * Recursively remaps coordinate sequences of a geometry to the target dimension profile
     * ({@link Coordinate}, {@link CoordinateXYM}, or {@link CoordinateXYZM}), preserving all
     * geometry types, nested collections, and empty geometries.
     */
    private static Geometry remapCoordinates(Geometry g, GeometryFactory gf, boolean hasZ, boolean hasM) {
        if (g == null) {
            return null;
        }
        int srid = g.getSRID();
        Geometry result;

        if (g.isEmpty()) {
            result = g.copy();
        } else if (g instanceof Point) {
            CoordinateSequence cs = remapSequence(((Point) g).getCoordinateSequence(), hasZ, hasM);
            result = gf.createPoint(cs);
        } else if (g instanceof LineString) {
            CoordinateSequence cs = remapSequence(((LineString) g).getCoordinateSequence(), hasZ, hasM);
            result = (g instanceof LinearRing) ? gf.createLinearRing(cs) : gf.createLineString(cs);
        } else if (g instanceof Polygon) {
            Polygon p = (Polygon) g;
            LinearRing shell = (LinearRing) remapCoordinates(p.getExteriorRing(), gf, hasZ, hasM);
            LinearRing[] holes = new LinearRing[p.getNumInteriorRing()];
            for (int i = 0; i < p.getNumInteriorRing(); i++) {
                holes[i] = (LinearRing) remapCoordinates(p.getInteriorRingN(i), gf, hasZ, hasM);
            }
            result = gf.createPolygon(shell, holes);
        } else if (g instanceof MultiPoint) {
            MultiPoint mp = (MultiPoint) g;
            Point[] points = new Point[mp.getNumGeometries()];
            for (int i = 0; i < mp.getNumGeometries(); i++) {
                points[i] = (Point) remapCoordinates(mp.getGeometryN(i), gf, hasZ, hasM);
            }
            result = gf.createMultiPoint(points);
        } else if (g instanceof MultiLineString) {
            MultiLineString mls = (MultiLineString) g;
            LineString[] lines = new LineString[mls.getNumGeometries()];
            for (int i = 0; i < mls.getNumGeometries(); i++) {
                lines[i] = (LineString) remapCoordinates(mls.getGeometryN(i), gf, hasZ, hasM);
            }
            result = gf.createMultiLineString(lines);
        } else if (g instanceof MultiPolygon) {
            MultiPolygon mp = (MultiPolygon) g;
            Polygon[] polys = new Polygon[mp.getNumGeometries()];
            for (int i = 0; i < mp.getNumGeometries(); i++) {
                polys[i] = (Polygon) remapCoordinates(mp.getGeometryN(i), gf, hasZ, hasM);
            }
            result = gf.createMultiPolygon(polys);
        } else if (g instanceof GeometryCollection) {
            GeometryCollection gc = (GeometryCollection) g;
            Geometry[] geoms = new Geometry[gc.getNumGeometries()];
            for (int i = 0; i < gc.getNumGeometries(); i++) {
                geoms[i] = remapCoordinates(gc.getGeometryN(i), gf, hasZ, hasM);
            }
            result = gf.createGeometryCollection(geoms);
        } else {
            throw new IllegalArgumentException("Unsupported geometry type: " + g.getClass().getName());
        }

        result.setSRID(srid);
        return result;
    }

    private static CoordinateSequence remapSequence(CoordinateSequence cs, boolean hasZ, boolean hasM) {
        if (cs == null || cs.size() == 0) {
            return cs;
        }
        if (hasZ && hasM) {
            CoordinateXYZM[] coords = new CoordinateXYZM[cs.size()];
            for (int i = 0; i < cs.size(); i++) {
                coords[i] = new CoordinateXYZM(ordinate(cs, i, 0), ordinate(cs, i, 1), ordinate(cs, i, 2), ordinate(cs, i, cs.getDimension() - 1));
            }
            return new CoordinateArraySequence(coords);
        } else if (hasM) {
            CoordinateXYM[] coords = new CoordinateXYM[cs.size()];
            for (int i = 0; i < cs.size(); i++) {
                // JTS read the M value into the last ordinate (slot 2 for 3-ordinate input)
                coords[i] = new CoordinateXYM(ordinate(cs, i, 0), ordinate(cs, i, 1), ordinate(cs, i, cs.getDimension() - 1));
            }
            return new CoordinateArraySequence(coords);
        } else if (hasZ) {
            Coordinate[] coords = new Coordinate[cs.size()];
            for (int i = 0; i < cs.size(); i++) {
                coords[i] = new Coordinate(ordinate(cs, i, 0), ordinate(cs, i, 1), ordinate(cs, i, 2));
            }
            return new CoordinateArraySequence(coords);
        } else {
            Coordinate[] coords = new Coordinate[cs.size()];
            for (int i = 0; i < cs.size(); i++) {
                coords[i] = new Coordinate(ordinate(cs, i, 0), ordinate(cs, i, 1));
            }
            return new CoordinateArraySequence(coords);
        }
    }

    /** Short, overflow-safe description of a database object for error messages. */
    private static String describe(Object databaseObject) {
        if (databaseObject instanceof byte[]) {
            return "byte[" + ((byte[]) databaseObject).length + "]";
        }
        String text = String.valueOf(databaseObject);
        return text.length() <= MAX_EXCEPTION_SNIPPET_LENGTH
                ? text
                : text.substring(0, MAX_EXCEPTION_SNIPPET_LENGTH) + "...(" + text.length() + " chars)";
    }
}
