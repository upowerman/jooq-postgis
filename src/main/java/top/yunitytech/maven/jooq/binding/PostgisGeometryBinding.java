package top.yunitytech.maven.jooq.binding;


import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.jetbrains.annotations.NotNull;
import org.jooq.*;
import org.locationtech.jts.geom.Geometry;
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
import java.util.Objects;


/**
 * @author gaoyunfeng
 */
public class PostgisGeometryBinding implements Binding<Object, Geometry> {

    private static final int CRS_4326 = 4326;

    @Override
    @NotNull
    public Converter<Object, Geometry> converter() {
        return new GeometryConverter();
    }

    public static class GeometryConverter implements Converter<Object, Geometry> {

        @Override
        public Geometry from(Object databaseObject) {
            if (databaseObject == null) {
                return null;
            }
            try {
                WKBReader reader = new WKBReader();
                return reader.read(WKBReader.hexToBytes(databaseObject.toString()));
            } catch (ParseException e) {
                throw new RuntimeException("Error parsing geometry WKB", e);
            }
        }

        @Override
        public Object to(Geometry userObject) {
            if (userObject == null) {
                return null;
            }
            Geometry transformedGeom = transformTo4326(userObject);
            WKBWriter writer = new WKBWriter(2, true);
            byte[] wkb = writer.write(transformedGeom);
            PGobject pgObject = new PGobject();
            pgObject.setType("geometry");
            try {
                pgObject.setValue(WKBWriter.toHex(wkb));
            } catch (SQLException e) {
                throw new RuntimeException("Error converting geometry to WKB", e);
            }
            return pgObject;
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
        Geometry geom = ctx.value();
        if (geom == null) {
            ctx.render().sql("?");
        } else {
            ctx.render().sql("ST_GeomFromWKB(decode(?, 'hex'))").sql("::geometry");
        }
    }

    @Override
    public void register(BindingRegisterContext<Geometry> ctx) throws SQLException {
        ctx.statement().registerOutParameter(ctx.index(), Types.VARCHAR);
    }

    @Override
    public void set(BindingSetStatementContext<Geometry> ctx) throws SQLException {
        Geometry geom = ctx.value();
        if (Objects.isNull(geom)) {
            ctx.statement().setNull(ctx.index(), Types.NULL);
        } else {
            Geometry transformedGeom = transformTo4326(geom);
            WKBWriter writer = new WKBWriter(2, true);
            String wkbHex = WKBWriter.toHex(writer.write(transformedGeom));
            ctx.statement().setString(ctx.index(), wkbHex);
        }
    }

    @Override
    public void get(BindingGetResultSetContext<Geometry> ctx) throws SQLException {
        Object obj = ctx.resultSet().getObject(ctx.index());
        ctx.value(converter().from(obj));
    }

    @Override
    public void get(BindingGetStatementContext<Geometry> ctx) throws SQLException {
        Object obj = ctx.statement().getObject(ctx.index());
        ctx.value(converter().from(obj));
    }

    @Override
    public void set(BindingSetSQLOutputContext<Geometry> ctx) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void get(BindingGetSQLInputContext<Geometry> ctx) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    /**
     * 将 Geometry 转换为  4326，如果需要则执行坐标变换。
     */
    private static Geometry transformTo4326(Geometry geom) {
        if (geom == null) {
            return null;
        }
        int crsId = geom.getSRID();
        if (crsId == CRS_4326) {
            return geom;
        }
        if (crsId == 0) {
            geom.setSRID(CRS_4326);
            return geom;
        }
        try {
            // 定义源和目标坐标系
            CoordinateReferenceSystem source = CRS.decode("EPSG:" + crsId);
            CoordinateReferenceSystem target = CRS.decode("EPSG:" + CRS_4326);
            MathTransform transform = CRS.findMathTransform(source, target);
            // 执行坐标变换
            Geometry transformedGeom = JTS.transform(geom, transform);
            transformedGeom.setSRID(CRS_4326);
            return transformedGeom;
        } catch (FactoryException e) {
            throw new RuntimeException("Failed to create CRS for SRID: " + crsId, e);
        } catch (TransformException e) {
            throw new RuntimeException("Failed to transform geometry to SRID 4326", e);
        }
    }
}
