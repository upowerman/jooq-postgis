package top.yunitytech.maven.jooq.binding;

import org.jetbrains.annotations.NotNull;
import org.jooq.*;
import org.jooq.conf.ParamType;
import org.locationtech.jts.geom.Geometry;
import org.postgresql.util.PGobject;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;

/**
 * Abstract base jOOQ binding for PostgreSQL / PostGIS spatial data types.
 * <p>
 * Implements the jOOQ {@link Binding} contract, delegating spatial serialization,
 * deserialization, and coordinate dimension validation to {@link PostgisCodec}.
 * <p>
 * Type parameters follow the jOOQ {@code <genericBinding>} instantiation protocol:
 * {@code T} is the database-side type jOOQ reports for the column and {@code U} is the
 * user type. jOOQ 3.14 reports PostGIS columns as {@code OTHER} ({@code T = Object});
 * jOOQ 3.15+ ships native spatial support, where geometry columns resolve to
 * {@code org.jooq.Geometry}. The user type is always JTS {@link Geometry}, so {@code U}
 * exists solely to satisfy the generic-binding constructor protocol and is not used
 * by the binding itself. Raw instantiation ({@code new PostgisGeometryBinding()})
 * remains fully supported for jOOQ 3.14 based projects.
 *
 * @param <T> the database-side type jOOQ reports for the bound column
 *            ({@code Object} on jOOQ 3.14, {@code org.jooq.Geometry} on jOOQ 3.15+)
 * @param <U> the user type (always JTS {@code Geometry} in practice)
 * @author gaoyunfeng
 */
public abstract class AbstractPostgisBinding<T, U> implements Binding<T, Geometry> {

    /**
     * Protected default constructor for subclasses (raw / jOOQ 3.14 instantiation).
     */
    protected AbstractPostgisBinding() {
    }

    /**
     * Protected constructor matching the jOOQ {@code <genericBinding>} instantiation protocol
     * ({@code new Binding<T, U>(Class<T>, Class<U>)}, used by jOOQ 3.15+ code generation).
     * The arguments are recorded for diagnostics only; the binding behaviour is independent
     * of the database-side type.
     *
     * @param databaseType the database-side type class reported by jOOQ codegen
     * @param userType     the user type class (JTS {@code Geometry})
     */
    protected AbstractPostgisBinding(Class<T> databaseType, Class<U> userType) {
    }

    /**
     * Returns the PostgreSQL spatial type name ("geometry" or "geography").
     *
     * @return spatial type name
     */
    public abstract String getSpatialTypeName();

    @Override
    @NotNull
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Converter<T, Geometry> converter() {
        return (Converter) SpatialConverter.INSTANCE;
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
        ctx.value(PostgisCodec.from(ctx.resultSet().getObject(ctx.index())));
    }

    @Override
    @SuppressWarnings("try")
    public void get(BindingGetStatementContext<Geometry> ctx) throws SQLException {
        ctx.value(PostgisCodec.from(ctx.statement().getObject(ctx.index())));
    }

    @Override
    public void set(BindingSetSQLOutputContext<Geometry> ctx) throws SQLException {
        throw new SQLFeatureNotSupportedException("SQLOutput not supported");
    }

    @Override
    public void get(BindingGetSQLInputContext<Geometry> ctx) throws SQLException {
        throw new SQLFeatureNotSupportedException("SQLInput not supported");
    }
}
