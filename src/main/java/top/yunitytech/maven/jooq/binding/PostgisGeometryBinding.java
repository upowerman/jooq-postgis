package top.yunitytech.maven.jooq.binding;


import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.jetbrains.annotations.NotNull;
import org.jooq.*;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;
import org.postgresql.util.PGobject;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * JOOQ binding for PostGIS geometry types.
 * <p>
 * Supports bidirectional conversion between JTS Geometry and PostGIS geometry,
 * with automatic coordinate transformation to a target SRID (default: EPSG:4326).
 * <p>
 * This binding caches coordinate transformations for better performance.
 *
 * @author gaoyunfeng
 */
public class PostgisGeometryBinding implements Binding<Object, Geometry> {

    private static final Logger LOGGER = Logger.getLogger(PostgisGeometryBinding.class.getName());
    private static final int DEFAULT_TARGET_SRID = 4326;
    private static final int SRID_UNKNOWN = 0;

    private static final ConcurrentMap<String, MathTransform> TRANSFORM_CACHE = new ConcurrentHashMap<>();

    private static final int TARGET_SRID = Integer.getInteger(
            "jooq.postgis.targetSrid", DEFAULT_TARGET_SRID);

    static {
        LOGGER.log(Level.INFO, "PostgisGeometryBinding initialized with target SRID: {0}", TARGET_SRID);
    }

    private final int targetSrid;

    public PostgisGeometryBinding() {
        this.targetSrid = TARGET_SRID;
    }

    public PostgisGeometryBinding(int targetSrid) {
        this.targetSrid = targetSrid;
    }

    @Override
    @NotNull
    public Converter<Object, Geometry> converter() {
        return GeometryConverter.INSTANCE;
    }

    /**
     * Pure type mapper: WKB hex String &harr; JTS Geometry.
     * No SRID transformation — that is the Binding's responsibility.
     */
    public static class GeometryConverter implements Converter<Object, Geometry> {

        public static final GeometryConverter INSTANCE = new GeometryConverter();

        @Override
        public Geometry from(Object databaseObject) {
            if (databaseObject == null) {
                return null;
            }
            try {
                String wkbHex = extractWkbHex(databaseObject);
                return new WKBReader().read(WKBReader.hexToBytes(wkbHex));
            } catch (ParseException e) {
                throw new RuntimeException("Error parsing geometry WKB from database object: " + databaseObject, e);
            }
        }

        private static String extractWkbHex(Object databaseObject) {
            if (databaseObject instanceof PGobject) {
                PGobject pgObj = (PGobject) databaseObject;
                if ("geometry".equals(pgObj.getType())) {
                    return pgObj.getValue();
                }
                throw new IllegalArgumentException("Unexpected PGobject type: " + pgObj.getType());
            }
            return databaseObject.toString();
        }

        @Override
        public Object to(Geometry userObject) {
            if (userObject == null) {
                return null;
            }
            return toWkbHex(userObject);
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
    }

    @Override
    public void sql(BindingSQLContext<Geometry> ctx) {
        if (ctx.value() == null) {
            ctx.render().sql("?");
        } else {
            ctx.render().sql("ST_GeomFromWKB(decode(?, 'hex'))").sql("::geometry");
        }
    }

    @Override
    @SuppressWarnings("try")
    public void register(BindingRegisterContext<Geometry> ctx) throws SQLException {
        ctx.statement().registerOutParameter(ctx.index(), Types.OTHER);
    }

    @Override
    @SuppressWarnings("try")
    public void set(BindingSetStatementContext<Geometry> ctx) throws SQLException {
        Geometry geom = ctx.value();
        if (geom == null) {
            ctx.statement().setNull(ctx.index(), Types.NULL);
        } else {
            Geometry transformed = transformGeometry(geom, targetSrid);
            ctx.statement().setString(ctx.index(), toWkbHex(transformed));
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

    private static Geometry transformGeometry(Geometry geom, int target) {
        if (geom == null || geom.isEmpty()) {
            return geom;
        }
        int sourceSrid = geom.getSRID();
        if (sourceSrid == target) {
            return geom;
        }
        if (sourceSrid == SRID_UNKNOWN) {
            throw new IllegalArgumentException(
                    "Cannot transform geometry with unknown SRID (0). Please set the SRID on the geometry before saving.");
        }

        try {
            MathTransform transform = getCachedTransform(sourceSrid, target);
            Geometry transformedGeom = JTS.transform(geom, transform);
            transformedGeom.setSRID(target);
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "Transformed geometry from EPSG:{0} to EPSG:{1}",
                        new Object[]{sourceSrid, target});
            }
            return transformedGeom;
        } catch (FactoryException e) {
            throw new RuntimeException(String.format(
                    "Failed to create CRS for SRID: %d. Please ensure the coordinate reference system is valid.",
                    sourceSrid), e);
        } catch (TransformException e) {
            throw new RuntimeException(String.format(
                    "Failed to transform geometry from EPSG:%d to EPSG:%d. The transformation may not be supported.",
                    sourceSrid, target), e);
        }
    }

    private static MathTransform getCachedTransform(int sourceSrid, int targetSrid) throws FactoryException {
        String key = sourceSrid + "->" + targetSrid;
        MathTransform cached = TRANSFORM_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        CoordinateReferenceSystem source = CRS.decode("EPSG:" + sourceSrid, true);
        CoordinateReferenceSystem target = CRS.decode("EPSG:" + targetSrid, true);
        MathTransform transform = CRS.findMathTransform(source, target);
        MathTransform existing = TRANSFORM_CACHE.putIfAbsent(key, transform);
        return existing != null ? existing : transform;
    }

    private static String toWkbHex(Geometry geom) {
        int dimension = getCoordinateDimension(geom);
        WKBWriter writer = new WKBWriter(dimension, true);
        return WKBWriter.toHex(writer.write(geom));
    }

    private static int getCoordinateDimension(Geometry geom) {
        if (geom == null || geom.isEmpty()) {
            return 2;
        }
        Geometry firstGeom = getFirstGeometry(geom);
        if (firstGeom == null || firstGeom.isEmpty()) {
            return 2;
        }
        org.locationtech.jts.geom.Coordinate coord = firstGeom.getCoordinate();
        if (coord == null) {
            return 2;
        }
        int dimension = 2;
        if (!Double.isNaN(coord.z)) {
            dimension = 3;
        }
        return dimension;
    }

    private static Geometry getFirstGeometry(Geometry geom) {
        if (geom instanceof GeometryCollection) {
            GeometryCollection collection = (GeometryCollection) geom;
            for (int i = 0; i < collection.getNumGeometries(); i++) {
                Geometry nested = collection.getGeometryN(i);
                if (!nested.isEmpty()) {
                    return nested;
                }
            }
            return geom;
        }
        return geom;
    }
}
