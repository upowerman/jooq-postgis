package top.yunitytech.maven.jooq.binding;

/**
 * JOOQ binding for PostGIS {@code geography} spatial type.
 * <p>
 * Handles bidirectional mapping between PostgreSQL {@code geography} columns
 * and JTS {@link org.locationtech.jts.geom.Geometry} objects in spherical / geodetic coordinate space.
 * <p>
 * Type parameters follow the jOOQ {@code <genericBinding>} protocol: {@code T} is the
 * database-side type ({@code Object} on jOOQ 3.14, {@code org.jooq.Geometry} on jOOQ 3.15+
 * code generation with {@code <genericBinding>true</genericBinding>}); {@code U} is the user
 * type (always JTS {@code Geometry}). Raw instantiation remains fully supported.
 *
 * @param <T> the database-side type
 * @param <U> the user type
 * @author gaoyunfeng
 */
public class PostgisGeographyBinding<T, U> extends AbstractPostgisBinding<T, U> {

    /**
     * PostgreSQL type name for geography.
     */
    public static final String TYPE_NAME = "geography";

    /**
     * Default constructor for jOOQ reflection, code generation (jOOQ 3.14), and raw usage.
     */
    public PostgisGeographyBinding() {
    }

    /**
     * Constructor matching the jOOQ {@code <genericBinding>} instantiation protocol (jOOQ 3.15+).
     *
     * @param databaseType the database-side type class reported by jOOQ codegen
     * @param userType     the user type class
     */
    public PostgisGeographyBinding(Class<T> databaseType, Class<U> userType) {
        super(databaseType, userType);
    }

    @Override
    public String getSpatialTypeName() {
        return TYPE_NAME;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PostgisGeographyBinding;
    }

    @Override
    public int hashCode() {
        return PostgisGeographyBinding.class.hashCode();
    }
}
