package top.yunitytech.maven.jooq.binding.internal;

import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.WKTReader;

/**
 * Thread-local cache pool for JTS spatial readers and writers.
 * <p>
 * JTS {@link WKBReader}, {@link WKBWriter}, and {@link WKTReader}
 * are stateful and not thread-safe. Reallocating them per operation causes unnecessary GC overhead
 * under high database throughput. This pool caches instances per-thread with zero contention.
 *
 * @author gaoyunfeng
 */
public final class SpatialWkbPool {

    /**
     * Standard GeometryFactory using CoordinateArraySequenceFactory.
     */
    public static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    /**
     * GeometryFactory supporting 4D coordinates (XYZM) for reading packed WKB sequences.
     */
    public static final GeometryFactory PACKED_GEOMETRY_FACTORY =
            new GeometryFactory(PackedCoordinateSequenceFactory.DOUBLE_FACTORY);

    private static final ThreadLocal<WKBReader> WKB_READER =
            ThreadLocal.withInitial(() -> new WKBReader(GEOMETRY_FACTORY));

    private static final ThreadLocal<WKBReader> PACKED_WKB_READER =
            ThreadLocal.withInitial(() -> new WKBReader(PACKED_GEOMETRY_FACTORY));

    private static final ThreadLocal<WKBWriter> WKB_WRITER_2D =
            ThreadLocal.withInitial(() -> new WKBWriter(2, true));

    private static final ThreadLocal<WKBWriter> WKB_WRITER_3D =
            ThreadLocal.withInitial(() -> new WKBWriter(3, true));

    private static final ThreadLocal<WKTReader> WKT_READER =
            ThreadLocal.withInitial(() -> new WKTReader(GEOMETRY_FACTORY));

    private SpatialWkbPool() {
        // Utility pool
    }

    /**
     * Returns a thread-local {@link WKBReader} configured with standard {@link #GEOMETRY_FACTORY}.
     */
    public static WKBReader getWkbReader() {
        return WKB_READER.get();
    }

    /**
     * Returns a thread-local {@link WKBReader} configured with {@link #PACKED_GEOMETRY_FACTORY}.
     */
    public static WKBReader getPackedWkbReader() {
        return PACKED_WKB_READER.get();
    }

    /**
     * Returns a thread-local {@link WKBWriter} configured for the given output dimension with SRID enabled.
     *
     * @param outputDimension coordinate dimension (2 or 3)
     * @return cached WKBWriter
     */
    public static WKBWriter getWkbWriter(int outputDimension) {
        return outputDimension >= 3 ? WKB_WRITER_3D.get() : WKB_WRITER_2D.get();
    }

    /**
     * Returns a thread-local {@link WKTReader} configured with {@link #GEOMETRY_FACTORY}.
     */
    public static WKTReader getWktReader() {
        return WKT_READER.get();
    }
}
