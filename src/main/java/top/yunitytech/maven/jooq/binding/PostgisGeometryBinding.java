package top.yunitytech.maven.jooq.binding;

/**
 * JOOQ binding for PostGIS {@code geometry} spatial type.
 * <p>
 * Handles bidirectional mapping between PostgreSQL {@code geometry} columns
 * and JTS {@link org.locationtech.jts.geom.Geometry} objects in Cartesian / planar coordinate space.
 *
 * @author gaoyunfeng
 */
public class PostgisGeometryBinding extends AbstractPostgisBinding {

    /**
     * PostgreSQL type name for geometry.
     */
    public static final String TYPE_NAME = "geometry";

    /**
     * Default constructor for jOOQ reflection and code generation.
     */
    public PostgisGeometryBinding() {
    }

    @Override
    public String getSpatialTypeName() {
        return TYPE_NAME;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PostgisGeometryBinding;
    }

    @Override
    public int hashCode() {
        return PostgisGeometryBinding.class.hashCode();
    }
}
