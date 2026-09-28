# jooq-postgis

English | [中文说明](README_CN.md)

[![Maven Central](https://img.shields.io/maven-central/v/top.yunitytech.maven/jooq-postgis.svg?color=brightgreen)](https://central.sonatype.com/artifact/top.yunitytech.maven/jooq-postgis)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-8%2B-orange.svg?logo=openjdk&logoColor=white)](https://www.oracle.com/java/)
[![jOOQ](https://img.shields.io/badge/jOOQ-3.14%2B-008080.svg)](https://www.jooq.org/)
[![PostGIS](https://img.shields.io/badge/PostGIS-3.x-275B89.svg?logo=postgresql&logoColor=white)](https://postgis.net/)
[![JTS](https://img.shields.io/badge/JTS-1.18%2B-green.svg)](https://github.com/locationtech/jts)

Lightweight, production-grade jOOQ spatial bindings for PostgreSQL / PostGIS mapping directly to JTS `org.locationtech.jts.geom.Geometry`.

Designed specifically for projects using `jooq-codegen-maven` to auto-generate type-safe JTS spatial fields with zero heavyweight runtime dependencies.

## Key Highlights

- **Pure JTS (Zero GeoTools)**: No bloated `gt-main` or OSGeo repository dependencies. Lightweight, fast startup, and 100% Maven Central compatible.
- **Dedicated Geometry & Geography Bindings**:
  - `PostgisGeometryBinding`: Strictly typed for `geometry` columns (planar / Cartesian space). Binds via `PGobject(type="geometry")` and renders `?::geometry`.
  - `PostgisGeographyBinding`: Strictly typed for `geography` columns (geodetic / spherical space). Binds via `PGobject(type="geography")` and renders `?::geography`.
  - Avoids semantic confusion and enables native functions (`ST_Distance`, `ST_DWithin`, `ST_Intersects`) without manual type casts.
- **Full Coordinate Dimension Support (2D, 3D, 3DM, 4D)**:
  - **2D (XY)**: Standard planar geometries.
  - **3D (XYZ)**: Elevation / altitude coordinates.
  - **3DM (XYM)**: Measure dimension (e.g., GPS timestamp, remote sensing sensor data).
  - **4D (XYZM)**: Satellite trajectories, 4D footprints (`POINT ZM`).
- **Safe JDBC PGobject Integration**:
  - Uses `PGobject` instead of `setString()` to eliminate PostgreSQL JDBC driver casting ambiguity.
  - Robust parser handles PostGIS EWKB Hex, PostGIS EWKT (`SRID=...;...`), standard WKT, and binary `byte[]` streams.
- **Safe PreparedStatement & Batching**: Correctly handles `NULL` values (`Types.OTHER`) and inlined SQL literals (`ParamType.INLINED`).

## Requirements

- Java 8+
- jOOQ 3.14+
- PostgreSQL with PostGIS extension
- JTS Core 1.18+

## Maven Dependency

Add `jooq-postgis` to your `pom.xml`:

```xml
<dependency>
    <groupId>top.yunitytech.maven</groupId>
    <artifactId>jooq-postgis</artifactId>
    <version>1.0.4</version>
</dependency>
```

> **Note:** `org.jooq:jooq` and `org.postgresql:postgresql` are provided with `provided` scope so your application controls their versions. `org.locationtech.jts:jts-core` is automatically included as a direct runtime dependency.

## Usage

### 1. Configure jOOQ Code Generator (`jooq-codegen-maven`)

In your code generation configuration, add `jooq-postgis` to the plugin `<dependencies>` and configure `<forcedTypes>`:

```xml
<plugin>
    <groupId>org.jooq</groupId>
    <artifactId>jooq-codegen-maven</artifactId>
    <version>${jooq.version}</version>
    <dependencies>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <version>${postgresql.version}</version>
        </dependency>
        <dependency>
            <groupId>top.yunitytech.maven</groupId>
            <artifactId>jooq-postgis</artifactId>
            <version>1.0.3</version>
        </dependency>
    </dependencies>
    <configuration>
        <generator>
            <database>
                <name>org.jooq.meta.postgres.PostgresDatabase</name>
                <forcedTypes>
                    <!-- Planar Cartesian geometry columns -->
                    <forcedType>
                        <userType>org.locationtech.jts.geom.Geometry</userType>
                        <binding>top.yunitytech.maven.jooq.binding.PostgisGeometryBinding</binding>
                        <includeTypes>(?i:geometry)</includeTypes>
                    </forcedType>
                    <!-- Geodetic spherical geography columns -->
                    <forcedType>
                        <userType>org.locationtech.jts.geom.Geometry</userType>
                        <binding>top.yunitytech.maven.jooq.binding.PostgisGeographyBinding</binding>
                        <includeTypes>(?i:geography)</includeTypes>
                    </forcedType>
                </forcedTypes>
            </database>
        </generator>
    </configuration>
</plugin>
```

### 2. Read & Write Spatial Data

Generated table records will now expose spatial fields directly as `org.locationtech.jts.geom.Geometry`:

```java
GeometryFactory gf = new GeometryFactory();

// 1. Insert 2D Geometry with SRID
Point beijing = gf.createPoint(new Coordinate(116.4074, 39.9042));
beijing.setSRID(4326);

dsl.insertInto(SPATIAL_RECORD)
   .set(SPATIAL_RECORD.ID, 1L)
   .set(SPATIAL_RECORD.GEOM, beijing)
   .execute();

// 2. Insert 4D Geometry (XYZM - e.g. Satellite Point ZM)
Point satelliteObs = gf.createPoint(new CoordinateXYZM(116.4, 39.9, 500000.0, 1695888000.0));
satelliteObs.setSRID(4326);

dsl.insertInto(SPATIAL_RECORD)
   .set(SPATIAL_RECORD.ID, 2L)
   .set(SPATIAL_RECORD.GEOM, satelliteObs)
   .execute();

// 3. Query back with full coordinate dimension retention
Geometry result = dsl.select(SPATIAL_RECORD.GEOM)
                     .from(SPATIAL_RECORD)
                     .where(SPATIAL_RECORD.ID.eq(2L))
                     .fetchOne(SPATIAL_RECORD.GEOM);

Coordinate coord = result.getCoordinate();
System.out.println("Z (Altitude): " + coord.getZ()); // 500000.0
System.out.println("M (Epoch): " + coord.getM());    // 1695888000.0
```

### 3. Native Geography Distance & DWithin

Because `PostgisGeographyBinding` binds directly to PostGIS `geography`, spherical calculations work natively in meters without casting:

```java
// Native spherical distance in meters
Double distanceMeters = dsl.select(
        DSL.field("ST_Distance({0}, {1})", Double.class, A.GEOG, B.GEOG)
    ).from(A).crossJoin(B)
    .fetchOne(0, Double.class);

// Native spherical DWithin (threshold: 200 km)
Boolean isNearby = dsl.select(
        DSL.field("ST_DWithin({0}, {1}, 200000)", Boolean.class, A.GEOG, B.GEOG)
    ).from(A).crossJoin(B)
    .fetchOne(0, Boolean.class);
```

### 4. Standalone Spatial Codec (`PostgisCodec`)

You can also use `PostgisCodec` directly outside of jOOQ (e.g. in custom JDBC queries, REST controllers, or queue consumers):

```java
// Decode PGobject, EWKB Hex, EWKT, or byte[] to JTS Geometry
Geometry geom = PostgisCodec.from(databaseObject);

// Encode JTS Geometry to PostGIS representation (EWKB Hex for 2D/3D, EWKT for 3DM/4D)
String repr = PostgisCodec.toSpatialRepresentation(geom);
```

## License

[Apache License 2.0](LICENSE)
