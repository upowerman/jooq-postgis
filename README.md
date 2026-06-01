# jooq-postgis

jOOQ Binding for PostGIS geometry types — provides seamless bidirectional conversion between PostGIS `geometry` columns and JTS `Geometry` objects in jOOQ.

## Features

- Bidirectional mapping between PostGIS geometry and JTS `Geometry`
- Automatic SRID coordinate transformation on write (default: EPSG:4326)
- Configurable target SRID via constructor or system property
- Coordinate transformation cache for better performance
- Auto-detects 2D/3D geometry dimensions

## Requirements

- Java 8+
- jOOQ 3.14+
- PostgreSQL with PostGIS extension
- JTS (locationtech-jts)
- GeoTools (gt-main, gt-referencing)

## Maven

```xml
<dependency>
    <groupId>top.yunitytech.maven</groupId>
    <artifactId>jooq-postgis</artifactId>
    <version>1.0.1</version>
</dependency>
```

All dependencies (jOOQ, PostgreSQL JDBC, GeoTools) are `provided` scope — make sure they are available in your project.

## Usage

### 1. Configure jOOQ Code Generation

In your jOOQ code generation configuration, register the binding as a custom type:

```xml
<database>
    <name>org.jooq.meta.postgres.PostgresDatabase</name>
    <!-- ... -->

    <customTypes>
        <customType>
            <name>org.locationtech.jts.geom.Geometry</name>
            <converter>top.yunitytech.maven.jooq.binding.PostgisGeometryBinding$GeometryConverter</converter>
        </customType>
    </customTypes>

    <forcedTypes>
        <forcedType>
            <userType>org.locationtech.jts.geom.Geometry</userType>
            <binding>top.yunitytech.maven.jooq.binding.PostgisGeometryBinding</binding>
            <includeExpression>.*\.geom(etry)?$</includeExpression>
            <includeTypes>geometry</includeTypes>
        </forcedType>
    </forcedTypes>
</database>
```

### 2. Read & Write Geometry

After code generation, geometry columns will be typed as JTS `Geometry`:

```java
// Write — SRID is automatically transformed to target (default EPSG:4326)
Point point = new GeometryFactory().createPoint(new Coordinate(116.4, 39.9));
point.setSRID(4326);

dsl.insertInto(PLACES)
   .set(PLACES.GEOM, point)
   .execute();

// Read — returns JTS Geometry in the database SRID
Geometry geom = dsl.select(PLACES.GEOM)
                   .from(PLACES)
                   .fetchOne(PLACES.GEOM);
```

### 3. Custom Target SRID

By default, geometries are transformed to **EPSG:4326** on write. You can change this:

**Via system property:**

```
-Djooq.postgis.targetSrid=3857
```

**Via constructor:**

```xml
<forcedType>
    <userType>org.locationtech.jts.geom.Geometry</userType>
    <binding>top.yunitytech.maven.jooq.binding.PostgisGeometryBinding</binding>
    <!-- In code, use: new PostgisGeometryBinding(3857) -->
</forcedType>
```

> **Note:** Geometries must have a non-zero SRID set before writing, otherwise an exception is thrown. Ensure you call `geometry.setSRID(srid)` before persisting.

## License

[Apache License 2.0](LICENSE)
