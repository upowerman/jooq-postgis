package top.yunitytech.maven.jooq.binding;

import org.jetbrains.annotations.NotNull;
import org.jooq.*;
import org.jooq.conf.ParamType;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.postgresql.util.PGobject;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;

/**
 * JOOQ binding for PostGIS {@code geometry} and {@code geography} types.
 * <p>
 * Provides seamless bidirectional conversion between PostGIS spatial types
 * and JTS {@link Geometry} objects in jOOQ.
 *
 * @author gaoyunfeng
 */
public class PostgisGeometryBinding implements Binding<Object, Geometry> {

    /**
     * Default constructor for jOOQ reflection and code generation.
     */
    public PostgisGeometryBinding() {
    }

    @Override
    @NotNull
    public Converter<Object, Geometry> converter() {
        return GeometryConverter.INSTANCE;
    }

    /**
     * Pure type mapper: PostGIS spatial object (EWKB hex String, PGobject, or byte[]) &harr; JTS Geometry.
     */
    public static class GeometryConverter implements Converter<Object, Geometry> {

        /**
         * Singleton instance of GeometryConverter.
         */
        public static final GeometryConverter INSTANCE = new GeometryConverter();

        /**
         * Default constructor.
         */
        public GeometryConverter() {
        }

        @Override
        public Geometry from(Object databaseObject) {
            if (databaseObject == null) {
                return null;
            }
            try {
                if (databaseObject instanceof PGobject) {
                    PGobject pgObj = (PGobject) databaseObject;
                    String type = pgObj.getType();
                    if ("geometry".equalsIgnoreCase(type) || "geography".equalsIgnoreCase(type)) {
                        String value = pgObj.getValue();
                        return value == null ? null : new WKBReader().read(WKBReader.hexToBytes(value));
                    }
                    throw new IllegalArgumentException("Unexpected PGobject type for spatial conversion: " + type);
                } else if (databaseObject instanceof byte[]) {
                    return new WKBReader().read((byte[]) databaseObject);
                } else if (databaseObject instanceof String) {
                    return new WKBReader().read(WKBReader.hexToBytes((String) databaseObject));
                }
                throw new IllegalArgumentException("Unsupported database object type for Geometry conversion: " + databaseObject.getClass().getName());
            } catch (ParseException e) {
                throw new RuntimeException("Error parsing geometry WKB from database object: " + databaseObject, e);
            }
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

        @Override
        public boolean equals(Object o) {
            return o instanceof GeometryConverter;
        }

        @Override
        public int hashCode() {
            return GeometryConverter.class.hashCode();
        }
    }

    @Override
    public void sql(BindingSQLContext<Geometry> ctx) {
        if (ctx.render().paramType() == ParamType.INLINED) {
            Geometry geom = ctx.value();
            if (geom == null) {
                ctx.render().sql("NULL::geometry");
            } else {
                ctx.render().sql("'" + toWkbHex(geom) + "'::geometry");
            }
        } else {
            ctx.render().sql("?::geometry");
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
            ctx.statement().setNull(ctx.index(), Types.OTHER);
        } else {
            ctx.statement().setString(ctx.index(), toWkbHex(geom));
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

    @Override
    public boolean equals(Object o) {
        return o instanceof PostgisGeometryBinding;
    }

    @Override
    public int hashCode() {
        return PostgisGeometryBinding.class.hashCode();
    }

    /**
     * Converts a JTS Geometry to an EWKB hexadecimal string, preserving SRID and coordinate dimension.
     *
     * @param geom the geometry to serialize
     * @return EWKB hex string
     */
    public static String toWkbHex(Geometry geom) {
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
        Coordinate coord = firstGeom.getCoordinate();
        if (coord == null) {
            return 2;
        }
        if (!Double.isNaN(coord.getZ())) {
            return 3;
        }
        return 2;
    }

    private static Geometry getFirstGeometry(Geometry geom) {
        while (geom instanceof GeometryCollection) {
            GeometryCollection collection = (GeometryCollection) geom;
            if (collection.isEmpty()) {
                return geom;
            }
            Geometry nonNull = null;
            for (int i = 0; i < collection.getNumGeometries(); i++) {
                Geometry nested = collection.getGeometryN(i);
                if (!nested.isEmpty()) {
                    nonNull = nested;
                    break;
                }
            }
            if (nonNull == null || nonNull == geom) {
                return geom;
            }
            geom = nonNull;
        }
        return geom;
    }
}
