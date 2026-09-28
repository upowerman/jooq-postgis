package top.yunitytech.maven.jooq.binding;

import org.jetbrains.annotations.NotNull;
import org.jooq.*;
import org.jooq.conf.ParamType;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.postgresql.util.PGobject;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;

/**
 * Abstract base jOOQ binding for PostgreSQL / PostGIS spatial data types.
 * <p>
 * Implements the jOOQ {@link Binding} contract, delegating spatial serialization,
 * deserialization, and coordinate dimension validation to {@link PostgisCodec}.
 *
 * @author gaoyunfeng
 */
public abstract class AbstractPostgisBinding implements Binding<Object, Geometry> {

    /**
     * Standard GeometryFactory (delegated to {@link PostgisCodec#GEOMETRY_FACTORY}).
     */
    public static final GeometryFactory GEOMETRY_FACTORY = PostgisCodec.GEOMETRY_FACTORY;

    /**
     * GeometryFactory supporting 4D coordinates (XYZM) (delegated to {@link PostgisCodec#PACKED_GEOMETRY_FACTORY}).
     */
    public static final GeometryFactory PACKED_GEOMETRY_FACTORY = PostgisCodec.PACKED_GEOMETRY_FACTORY;

    /**
     * Protected default constructor for subclasses.
     */
    protected AbstractPostgisBinding() {
    }

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
     * jOOQ Converter between database representation and JTS {@link Geometry}.
     * Delegates all parsing and conversion to {@link PostgisCodec}.
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
            return PostgisCodec.from(databaseObject);
        }

        @Override
        public Object to(Geometry userObject) {
            return PostgisCodec.toSpatialRepresentation(userObject);
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
            String repr = PostgisCodec.toSpatialRepresentation(geom);
            if (repr == null) {
                ctx.render().sql("NULL::" + typeName);
            } else {
                ctx.render().visit(org.jooq.impl.DSL.inline(repr)).sql("::" + typeName);
            }
        } else {
            ctx.render().sql(ctx.variable()).sql("::" + typeName);
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
            pgObj.setValue(PostgisCodec.toSpatialRepresentation(geom));
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

    // =========================================================================
    // Backward Compatibility Forwarders to PostgisCodec
    // =========================================================================

    /**
     * Converts a JTS Geometry to an optimal representation for PostgreSQL.
     *
     * @param geom the geometry to serialize
     * @return spatial representation string
     * @see PostgisCodec#toSpatialRepresentation(Geometry)
     */
    public static String toSpatialRepresentation(Geometry geom) {
        return PostgisCodec.toSpatialRepresentation(geom);
    }

    /**
     * Fixes JTS WKTWriter missing space before EMPTY for dimensioned types.
     *
     * @param wkt WKT string
     * @return normalized WKT string
     * @see PostgisCodec#fixWktEmptySpacing(String)
     */
    public static String fixWktEmptySpacing(String wkt) {
        return PostgisCodec.fixWktEmptySpacing(wkt);
    }

    /**
     * Validates that all non-empty coordinates within the geometry have consistent dimensions.
     *
     * @param geom geometry to validate
     * @see PostgisCodec#validateDimensionConsistency(Geometry)
     */
    public static void validateDimensionConsistency(Geometry geom) {
        PostgisCodec.validateDimensionConsistency(geom);
    }

    /**
     * Checks if a string is a valid hexadecimal EWKB representation.
     *
     * @param s candidate string
     * @return true if string is even-length hex starting with 00 or 01
     * @see PostgisCodec#isHex(String)
     */
    public static boolean isHex(String s) {
        return PostgisCodec.isHex(s);
    }

    /**
     * Filter to verify all non-empty coordinates within a geometry have consistent dimensions.
     * Backward-compatible alias for {@link PostgisCodec.DimensionFilter}.
     */
    public static class DimensionFilter extends PostgisCodec.DimensionFilter {
        /**
         * Default constructor.
         */
        public DimensionFilter() {
            super();
        }
    }
}
