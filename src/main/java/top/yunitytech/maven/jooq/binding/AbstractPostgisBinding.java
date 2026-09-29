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

    protected final Class<T> databaseType;
    protected final Class<U> userType;
    protected final Converter<T, Geometry> converter;

    /**
     * Protected default constructor for subclasses (raw / jOOQ 3.14 instantiation).
     */
    @SuppressWarnings("unchecked")
    protected AbstractPostgisBinding() {
        this((Class<T>) Object.class, (Class<U>) Geometry.class);
    }

    /**
     * Protected constructor matching the jOOQ {@code <genericBinding>} instantiation protocol
     * ({@code new Binding<T, U>(Class<T>, Class<U>)}, used by jOOQ 3.15+ code generation).
     *
     * @param databaseType the database-side type class reported by jOOQ codegen
     * @param userType     the user type class (JTS {@code Geometry})
     */
    @SuppressWarnings("unchecked")
    protected AbstractPostgisBinding(Class<T> databaseType, Class<U> userType) {
        this.databaseType = databaseType != null ? databaseType : (Class<T>) Object.class;
        this.userType = userType != null ? userType : (Class<U>) Geometry.class;
        this.converter = new SpatialConverter<>(this.databaseType);
    }

    /**
     * Returns the database-side type class for this binding.
     *
     * @return database-side type class
     */
    public Class<T> getDatabaseType() {
        return databaseType;
    }

    /**
     * Returns the user-side type class for this binding.
     *
     * @return user-side type class
     */
    public Class<U> getUserType() {
        return userType;
    }

    /**
     * Returns the PostgreSQL spatial type name ("geometry" or "geography").
     *
     * @return spatial type name
     */
    public abstract String getSpatialTypeName();

    @Override
    @NotNull
    public Converter<T, Geometry> converter() {
        return converter;
    }

    /**
     * jOOQ Converter between database representation and JTS {@link Geometry}.
     * Delegates all parsing and conversion to {@link PostgisCodec}.
     *
     * @param <T> the database-side type
     */
    public static class SpatialConverter<T> implements Converter<T, Geometry> {
        private static final long serialVersionUID = 1L;

        private static final java.util.concurrent.ConcurrentMap<Class<?>, java.util.function.Function<String, ?>> FACTORIES =
                new java.util.concurrent.ConcurrentHashMap<>();

        /**
         * Singleton instance of the spatial converter for Object database type (raw usage).
         */
        public static final SpatialConverter<Object> INSTANCE = new SpatialConverter<>(Object.class);

        private final Class<T> databaseType;

        /**
         * Default constructor defaulting databaseType to Object.class.
         */
        @SuppressWarnings("unchecked")
        public SpatialConverter() {
            this((Class<T>) Object.class);
        }

        /**
         * Constructor binding to a specific databaseType class.
         *
         * @param databaseType database-side type class
         */
        public SpatialConverter(Class<T> databaseType) {
            this.databaseType = databaseType != null ? databaseType : (Class<T>) Object.class;
        }

        @Override
        public Geometry from(T databaseObject) {
            return PostgisCodec.from(databaseObject);
        }

        @Override
        @SuppressWarnings("unchecked")
        public T to(Geometry userObject) {
            if (userObject == null) {
                return null;
            }
            String repr = PostgisCodec.toSpatialRepresentation(userObject);
            if (databaseType == Object.class || databaseType == String.class || databaseType.isAssignableFrom(String.class)) {
                return (T) repr;
            }
            if (PGobject.class.isAssignableFrom(databaseType)) {
                try {
                    PGobject pg = new PGobject();
                    pg.setValue(repr);
                    return (T) pg;
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to set PGobject value", e);
                }
            }
            return toDatabaseType(repr, databaseType);
        }

        @SuppressWarnings("unchecked")
        private static <T> T toDatabaseType(String repr, Class<T> targetType) {
            java.util.function.Function<String, ?> factory = FACTORIES.computeIfAbsent(targetType, SpatialConverter::findFactory);
            return (T) factory.apply(repr);
        }

        private static java.util.function.Function<String, ?> findFactory(Class<?> type) {
            try {
                java.lang.reflect.Method m = type.getMethod("valueOf", String.class);
                return s -> {
                    try {
                        return m.invoke(null, s);
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException("Failed to convert string to " + type.getName(), e);
                    }
                };
            } catch (NoSuchMethodException ignored) {
            }
            try {
                java.lang.reflect.Constructor<?> c = type.getConstructor(String.class);
                return s -> {
                    try {
                        return c.newInstance(s);
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException("Failed to instantiate " + type.getName(), e);
                    }
                };
            } catch (NoSuchMethodException ignored) {
            }
            return s -> s;
        }

        @Override
        @NotNull
        public Class<T> fromType() {
            return databaseType;
        }

        @Override
        @NotNull
        public Class<Geometry> toType() {
            return Geometry.class;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SpatialConverter)) return false;
            SpatialConverter<?> that = (SpatialConverter<?>) o;
            return java.util.Objects.equals(databaseType, that.databaseType);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(SpatialConverter.class, databaseType);
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AbstractPostgisBinding<?, ?> that = (AbstractPostgisBinding<?, ?>) o;
        return java.util.Objects.equals(databaseType, that.databaseType) &&
                java.util.Objects.equals(userType, that.userType);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(getClass(), databaseType, userType);
    }
}
