# jooq-postgis

English | [中文说明](README_CN.md)

[![Maven Central](https://img.shields.io/maven-central/v/top.yunitytech.maven/jooq-postgis.svg?color=brightgreen)](https://central.sonatype.com/artifact/top.yunitytech.maven/jooq-postgis)
[![CI](https://github.com/upowerman/jooq-postgis/actions/workflows/ci.yml/badge.svg)](https://github.com/upowerman/jooq-postgis/actions/workflows/ci.yml)
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

- Java 8+ (compiled with `--release 8`)
- jOOQ 3.14+ (runtime; for code generation on jOOQ 3.15+ add `<genericBinding>true</genericBinding>` to each `<forcedType>` — see below)
- **Code generation on jOOQ 3.14–3.19 requires pgjdbc ≤ 42.7.4.** pgjdbc 42.7.5+ reports JDBC-spec-compliant uppercase metadata labels, which jOOQ reads case-sensitively, breaking code generation for every table ([jOOQ #17873](https://github.com/jOOQ/jOOQ/issues/17873) — fixed in jOOQ 3.20.0 only). Runtime usage of this library is unaffected by the driver version. jOOQ 3.20+ codegen works with any pgjdbc, but jOOQ 3.20 ships Java 21 bytecode and therefore requires Java 21.
- PostgreSQL with PostGIS extension (PostgreSQL/PostGIS dialect only)
- JTS Core 1.18+

## Maven Dependency

Add `jooq-postgis` to your `pom.xml`:

```xml
<dependency>
    <groupId>top.yunitytech.maven</groupId>
    <artifactId>jooq-postgis</artifactId>
    <version>1.0.8</version>
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
            <version>1.0.8</version>
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

> **jOOQ 3.15+ note:** jOOQ 3.15 introduced native spatial support, so the generator resolves
> `geometry` columns to `org.jooq.Geometry` instead of `OTHER`. On jOOQ 3.15+ (including 3.19.x)
> add `<genericBinding>true</genericBinding>` to **both** `<forcedType>` entries above — the
> bindings then instantiate through their `(Class<T>, Class<U>)` constructors and the generated
> code compiles. On jOOQ 3.14 the element does not exist and must be omitted.

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

// Encode JTS Geometry to PostGIS representation (big-endian EWKB Hex for all
// dimensions: XY / XYZ / XYM / XYZM, with SRID embedded when non-zero)
String repr = PostgisCodec.toSpatialRepresentation(geom);
```

## Correctness & Edge-Case Semantics

The codec's dimension handling mirrors PostgreSQL / PostGIS semantics as closely as JTS allows:

- **NaN is a legal ordinate value, not a dimension signal.** `LINESTRING Z(0 0 NaN, 1 1 5)` — accepted by PostGIS with `ST_NDims = 3` — round-trips losslessly. Within a single geometry, a Z/M dimension counts as present when any coordinate carries a non-NaN value.
- **Typed JTS sequences win.** Geometries built from `CoordinateXYM` / `CoordinateXYZM` (or packed sequences with measures) keep their declared dimension even when all M values are NaN; plain `Coordinate` sequences are classified by ordinate values.
- **Collections are dimension-strict.** `GEOMETRYCOLLECTION(POINT(1 2), POINT Z(3 4 5))` is rejected with `IllegalArgumentException`, exactly like PostGIS rejects it (`Dimensions mismatch in lwcollection`).
- **Foreign (non-PostGIS) WKB is decoded by leaf flags.** Some ISO WKB writers under-declare collection headers. `fromWkb` profiles the Z/M flags of every leaf geometry (JTS parses each element by its own flags), so `POINT M` children under a 2D collection header decode correctly to XYM instead of silently re-interpreting M as Z. Leaf flags must be uniform across the stream.
- **EMPTY geometries serialize as 2D EWKB; writing them into Z/M/ZM-typmod columns is rejected by the server.** JTS cannot represent typed empties ("POINT Z EMPTY") — an empty geometry carries no dimension information, so it serializes as 2D and PostgreSQL answers `Column has Z dimension but geometry does not`. Reading is unaffected: dimensioned empties written by PostGIS decode to empty JTS geometries. When the column type is known, declare the dimension explicitly:
  ```java
  String hex = PostgisCodec.toSpatialRepresentation(emptyPoint, DimensionAnalyzer.CoordinateDimension.XYZ); // "POINT Z EMPTY"
  ```
- **Only simple OGC geometry types are supported.** Point, LineString, Polygon, Multi*, and GeometryCollection (WKB types 1–7) map to JTS types. Curve/surface types (`CIRCULARSTRING`, `COMPOUNDCURVE`, `CURVEPOLYGON`, `TRIANGLE`, `POLYHEDRALSURFACE`, …) have no JTS representation and are rejected with an exception — since such columns still codegen as `Geometry`, every fetch of a row containing one will throw. Exclude such tables/columns by name in codegen (`<excludes>`).
- **Collections must not mix non-zero SRIDs.** EWKB embeds only the root SRID; a non-empty component declaring a different non-zero SRID is rejected (`IllegalArgumentException`) instead of being silently lost. Components with SRID 0 (unset) inherit the root's SRID.
- **typmod and SRID are enforced by PostgreSQL.** `geometry(Point,4326)` constraints and SRID mismatches are rejected by the server — always call `setSRID()` on user-built geometries. The binding itself is transport-only.
- **PostgreSQL / PostGIS only.** The `?::geometry` / `?::geography` casts are rendered regardless of the configured SQL dialect; other databases are not supported.
- **Tighter codegen matching (optional).** If your schema contains custom type names containing `geometry`/`geography`, anchor the patterns (e.g. `(?i:^geometry$)`).

## Testing & CI

Integration tests run against a live PostgreSQL + PostGIS instance and skip gracefully when none is reachable:

```bash
docker run --name postgis-test -e POSTGRES_PASSWORD=postgres -p 5432:5432 -d postgis/postgis:16-3.4
docker exec postgis-test createdb -U postgres test_db
mvn test
```

Connection settings resolve from system properties (`test.db.url`, `test.db.user`, `test.db.password`) or environment variables (`TEST_DB_URL`, `TEST_DB_USER`, `TEST_DB_PASSWORD`), defaulting to `localhost:5432/test_db` with `postgres/postgres`.

CI (GitHub Actions) runs the full suite on every push and pull request — a JDK 17/21 × jOOQ 3.14.16/3.19.10 matrix against a `postgis/postgis:16-3.4` service container. When testing locally against jOOQ 3.16+ with `-Djooq.version=...`, add `-Dmaven.compiler.release=17`: jOOQ 3.16+ requires Java 17+ at runtime and its reactive-API signatures (`java.util.concurrent.Flow`) are invisible to `--release 8` compilation. The released artifact remains Java 8 bytecode.

## License

[Apache License 2.0](LICENSE)
