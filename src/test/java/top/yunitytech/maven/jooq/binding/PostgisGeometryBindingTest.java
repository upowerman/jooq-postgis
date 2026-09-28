package top.yunitytech.maven.jooq.binding;

import org.jooq.*;
import org.jooq.conf.ParamType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.postgresql.util.PGobject;

import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgisGeometryBindingTest {

    private final GeometryFactory gf = new GeometryFactory();
    private PostgisGeometryBinding binding;
    private Converter<Object, Geometry> converter;

    @BeforeEach
    void setUp() {
        binding = new PostgisGeometryBinding();
        converter = binding.converter();
    }

    @Nested
    @DisplayName("GeometryConverter Tests")
    class ConverterTests {

        @Test
        @DisplayName("null input returns null for both directions")
        void testNullHandling() {
            assertThat(converter.from(null)).isNull();
            assertThat(converter.to(null)).isNull();
        }

        @Test
        @DisplayName("2D Point with SRID 4326 converts to EWKB hex and back")
        void testPoint2DRoundTrip() {
            Point point = gf.createPoint(new Coordinate(116.4, 39.9));
            point.setSRID(4326);

            Object hexObj = converter.to(point);
            assertThat(hexObj).isInstanceOf(String.class);

            String hex = (String) hexObj;
            Geometry parsed = converter.from(hex);

            assertThat(parsed).isInstanceOf(Point.class);
            assertThat(parsed.getSRID()).isEqualTo(4326);
            assertThat(parsed.getCoordinate().x).isEqualTo(116.4);
            assertThat(parsed.getCoordinate().y).isEqualTo(39.9);
        }

        @Test
        @DisplayName("3D Point with SRID 3857 preserves Z dimension and SRID")
        void testPoint3DRoundTrip() {
            Point point = gf.createPoint(new Coordinate(100.0, 20.0, 50.0));
            point.setSRID(3857);

            Object hexObj = converter.to(point);
            Geometry parsed = converter.from(hexObj);

            assertThat(parsed.getSRID()).isEqualTo(3857);
            assertThat(parsed.getCoordinate().x).isEqualTo(100.0);
            assertThat(parsed.getCoordinate().y).isEqualTo(20.0);
            assertThat(parsed.getCoordinate().getZ()).isEqualTo(50.0);
        }

        @Test
        @DisplayName("Polygon with SRID roundtrip")
        void testPolygonRoundTrip() {
            Coordinate[] coords = new Coordinate[]{
                    new Coordinate(0, 0),
                    new Coordinate(0, 10),
                    new Coordinate(10, 10),
                    new Coordinate(10, 0),
                    new Coordinate(0, 0)
            };
            Polygon polygon = gf.createPolygon(coords);
            polygon.setSRID(4326);

            Object hex = converter.to(polygon);
            Geometry parsed = converter.from(hex);

            assertThat(parsed).isInstanceOf(Polygon.class);
            assertThat(parsed.getSRID()).isEqualTo(4326);
            assertThat(parsed.equalsExact(polygon)).isTrue();
        }

        @Test
        @DisplayName("Converter accepts PGobject with type 'geometry'")
        void testPGobjectGeometry() throws SQLException {
            Point point = gf.createPoint(new Coordinate(1.0, 2.0));
            point.setSRID(4326);
            String hex = (String) converter.to(point);

            PGobject pgObject = new PGobject();
            pgObject.setType("geometry");
            pgObject.setValue(hex);

            Geometry result = converter.from(pgObject);
            assertThat(result).isNotNull();
            assertThat(result.getSRID()).isEqualTo(4326);
            assertThat(result.getCoordinate().x).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Converter accepts PGobject with type 'geography'")
        void testPGobjectGeography() throws SQLException {
            Point point = gf.createPoint(new Coordinate(1.0, 2.0));
            point.setSRID(4326);
            String hex = (String) converter.to(point);

            PGobject pgObject = new PGobject();
            pgObject.setType("geography");
            pgObject.setValue(hex);

            Geometry result = converter.from(pgObject);
            assertThat(result).isNotNull();
            assertThat(result.getSRID()).isEqualTo(4326);
        }

        @Test
        @DisplayName("Converter handles PGobject with null value")
        void testPGobjectNullValue() throws SQLException {
            PGobject pgObject = new PGobject();
            pgObject.setType("geometry");
            pgObject.setValue(null);

            Geometry result = converter.from(pgObject);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Converter accepts raw binary byte[] WKB")
        void testByteArraySupport() {
            Point point = gf.createPoint(new Coordinate(1.0, 2.0));
            point.setSRID(4326);
            byte[] bytes = new WKBWriter(2, true).write(point);

            Geometry result = converter.from(bytes);
            assertThat(result).isNotNull();
            assertThat(result.getSRID()).isEqualTo(4326);
        }

        @Test
        @DisplayName("Converter rejects unsupported PGobject type")
        void testUnexpectedPGobjectType() throws SQLException {
            PGobject pgObject = new PGobject();
            pgObject.setType("jsonb");
            pgObject.setValue("{}");

            assertThatThrownBy(() -> converter.from(pgObject))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("jsonb");
        }

        @Test
        @DisplayName("Converter types are Object and Geometry")
        void testConverterTypes() {
            assertThat(converter.fromType()).isEqualTo(Object.class);
            assertThat(converter.toType()).isEqualTo(Geometry.class);
        }
    }

    @Nested
    @DisplayName("Binding SQL Rendering Tests")
    class SqlRenderingTests {

        @Test
        @DisplayName("sql() renders '?::geometry' for standard statement mode whether value is null or not")
        void testStandardSqlRendering() {
            StringBuilder sb = new StringBuilder();
            BindingSQLContext<Geometry> ctx = createBindingSQLContext(null, ParamType.INDEXED, sb);
            binding.sql(ctx);
            assertThat(sb.toString()).isEqualTo("?::geometry");

            Point point = gf.createPoint(new Coordinate(1, 2));
            sb.setLength(0);
            BindingSQLContext<Geometry> ctx2 = createBindingSQLContext(point, ParamType.INDEXED, sb);
            binding.sql(ctx2);
            assertThat(sb.toString()).isEqualTo("?::geometry");
        }

        @Test
        @DisplayName("sql() renders inlined literal when paramType is INLINED")
        void testInlinedSqlRendering() {
            StringBuilder sb = new StringBuilder();
            Point point = gf.createPoint(new Coordinate(1, 2));
            point.setSRID(4326);

            BindingSQLContext<Geometry> ctx = createBindingSQLContext(point, ParamType.INLINED, sb);
            binding.sql(ctx);

            String expectedHex = (String) converter.to(point);
            assertThat(sb.toString()).isEqualTo("'" + expectedHex + "'::geometry");

            // Inlined null
            sb.setLength(0);
            BindingSQLContext<Geometry> nullCtx = createBindingSQLContext(null, ParamType.INLINED, sb);
            binding.sql(nullCtx);
            assertThat(sb.toString()).isEqualTo("NULL::geometry");
        }
    }

    @Nested
    @DisplayName("Binding Statement & ResultSet Binding Tests")
    class StatementBindingTests {

        @Test
        @DisplayName("set() binds hex string into PreparedStatement for non-null Geometry")
        void testSetNonNull() throws Exception {
            Point point = gf.createPoint(new Coordinate(10.0, 20.0));
            point.setSRID(4326);

            AtomicInteger boundIndex = new AtomicInteger();
            AtomicReference<String> boundValue = new AtomicReference<>();

            PreparedStatement stmt = (PreparedStatement) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {
                        if ("setString".equals(method.getName())) {
                            boundIndex.set((Integer) args[0]);
                            boundValue.set((String) args[1]);
                        }
                        return null;
                    });

            BindingSetStatementContext<Geometry> ctx = createSetStatementContext(point, 1, stmt);
            binding.set(ctx);

            assertThat(boundIndex.get()).isEqualTo(1);
            assertThat(boundValue.get()).isNotNull();

            Geometry restored = new WKBReader().read(WKBReader.hexToBytes(boundValue.get()));
            assertThat(restored.getSRID()).isEqualTo(4326);
            assertThat(restored.getCoordinate().x).isEqualTo(10.0);
        }

        @Test
        @DisplayName("set() calls setNull with Types.OTHER for null Geometry")
        void testSetNull() throws SQLException {
            AtomicInteger boundIndex = new AtomicInteger();
            AtomicInteger boundType = new AtomicInteger();

            PreparedStatement stmt = (PreparedStatement) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {
                        if ("setNull".equals(method.getName())) {
                            boundIndex.set((Integer) args[0]);
                            boundType.set((Integer) args[1]);
                        }
                        return null;
                    });

            BindingSetStatementContext<Geometry> ctx = createSetStatementContext(null, 2, stmt);
            binding.set(ctx);

            assertThat(boundIndex.get()).isEqualTo(2);
            assertThat(boundType.get()).isEqualTo(Types.OTHER);
        }

        @Test
        @DisplayName("get() from ResultSet converts database object to Geometry")
        void testGetResultSet() throws SQLException {
            Point point = gf.createPoint(new Coordinate(5, 10));
            point.setSRID(4326);
            String hex = (String) converter.to(point);

            PGobject pg = new PGobject();
            pg.setType("geometry");
            pg.setValue(hex);

            ResultSet rs = (ResultSet) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> {
                        if ("getObject".equals(method.getName()) && Integer.valueOf(1).equals(args[0])) {
                            return pg;
                        }
                        return null;
                    });

            AtomicReference<Geometry> resultValue = new AtomicReference<>();
            BindingGetResultSetContext<Geometry> ctx = createGetResultSetContext(rs, 1, resultValue);

            binding.get(ctx);

            assertThat(resultValue.get()).isNotNull();
            assertThat(resultValue.get().getSRID()).isEqualTo(4326);
            assertThat(resultValue.get().getCoordinate().x).isEqualTo(5.0);
        }
    }

    @Nested
    @DisplayName("Binding Contract & Equality Tests")
    class ContractTests {

        @Test
        @DisplayName("equals and hashCode contract")
        void testEqualsAndHashCode() {
            PostgisGeometryBinding b1 = new PostgisGeometryBinding();
            PostgisGeometryBinding b2 = new PostgisGeometryBinding();

            assertThat(b1).isEqualTo(b2);
            assertThat(b1.hashCode()).isEqualTo(b2.hashCode());
            assertThat(b1).isNotEqualTo(null);
            assertThat(b1).isNotEqualTo("other");
        }
    }

    // Helper proxy creators for jOOQ contexts

    private BindingSQLContext<Geometry> createBindingSQLContext(Geometry value, ParamType paramType, StringBuilder sqlBuffer) {
        RenderContext renderContext = (RenderContext) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{RenderContext.class},
                (proxy, method, args) -> {
                    if ("sql".equals(method.getName())) {
                        sqlBuffer.append(args[0]);
                        return proxy;
                    }
                    if ("paramType".equals(method.getName())) {
                        return paramType;
                    }
                    return null;
                });

        return (BindingSQLContext<Geometry>) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{BindingSQLContext.class},
                (proxy, method, args) -> {
                    if ("value".equals(method.getName())) return value;
                    if ("render".equals(method.getName())) return renderContext;
                    return null;
                });
    }

    private BindingSetStatementContext<Geometry> createSetStatementContext(Geometry value, int index, PreparedStatement stmt) {
        return (BindingSetStatementContext<Geometry>) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{BindingSetStatementContext.class},
                (proxy, method, args) -> {
                    if ("value".equals(method.getName())) return value;
                    if ("index".equals(method.getName())) return index;
                    if ("statement".equals(method.getName())) return stmt;
                    return null;
                });
    }

    private BindingGetResultSetContext<Geometry> createGetResultSetContext(ResultSet rs, int index, AtomicReference<Geometry> out) {
        return (BindingGetResultSetContext<Geometry>) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{BindingGetResultSetContext.class},
                (proxy, method, args) -> {
                    if ("resultSet".equals(method.getName())) return rs;
                    if ("index".equals(method.getName())) return index;
                    if ("value".equals(method.getName()) && args != null && args.length == 1) {
                        out.set((Geometry) args[0]);
                        return null;
                    }
                    return null;
                });
    }
}
