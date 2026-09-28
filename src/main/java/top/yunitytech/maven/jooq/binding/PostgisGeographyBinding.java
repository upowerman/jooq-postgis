package top.yunitytech.maven.jooq.binding;

/**
 * JOOQ binding for PostGIS {@code geography} spatial type.
 * <p>
 * Handles bidirectional mapping between PostgreSQL {@code geography} columns
 * and JTS {@link org.locationtech.jts.geom.Geometry} objects in spherical / geodetic coordinate space.
 *
 * @author gaoyunfeng
 */
public class PostgisGeographyBinding extends AbstractPostgisBinding {

    /**
     * PostgreSQL type name for geography.
     */
    public static final String TYPE_NAME = "geography";

    /**
     * Default constructor for jOOQ reflection and code generation.
     */
    public PostgisGeographyBinding() {
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
