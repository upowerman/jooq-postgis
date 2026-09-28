# jooq-postgis

English | [中文说明](README_CN.md)

Lightweight jOOQ Binding for PostGIS `geometry` and `geography` types — provides seamless bidirectional conversion between PostgreSQL spatial columns and JTS `Geometry` objects.

## Features

- **Pure JTS**: Zero GeoTools dependency, fast, lightweight, and Maven Central friendly.
- **Full Type Support**: Supports both PostGIS `geometry` and `geography` types.
- **EWKB Native**: Preserves SRID and coordinate dimensions (2D / 3D) seamlessly across read and write.
- **Binary & Hex Tolerant**: Handles PGobject, hex strings, and binary byte streams.
- **Safe PreparedStatement & Batching**: Robust SQL rendering for NULL values and inlined parameters (`ParamType.INLINED`).
- **jOOQ Codegen Ready**: Easily integrated with `jooq-codegen-maven` to auto-generate JTS Geometry fields.

## Requirements

- Java 8+
- jOOQ 3.14+
- PostgreSQL with PostGIS extension
- JTS Core 1.18+

## Maven Dependency

Add this dependency to your project:

```xml
<dependency>
    <groupId>top.yunitytech.maven</groupId>
    <artifactId>jooq-postgis</artifactId>
    <version>1.0.2</version>
</dependency>
```

> **Note:** `org.jooq:jooq` and `org.postgresql:postgresql` are `provided` scope. Make sure your application supplies them. `org.locationtech.jts:jts-core` is automatically included as a direct dependency.

## Usage

### 1. Configure jOOQ Code Generation

In your `jooq-codegen-maven` plugin configuration, add `jooq-postgis` to the plugin's `<dependencies>` and configure `<forcedTypes>`:

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
            <version>1.0.2</version>
        </dependency>
    </dependencies>
    <configuration>
        <generator>
            <database>
                <name>org.jooq.meta.postgres.PostgresDatabase</name>
                <!-- Include your schemas / tables -->
                <forcedTypes>
                    <forcedType>
                        <userType>org.locationtech.jts.geom.Geometry</userType>
                        <binding>top.yunitytech.maven.jooq.binding.PostgisGeometryBinding</binding>
                        <includeTypes>(?i:geometry|geography)</includeTypes>
                    </forcedType>
                </forcedTypes>
            </database>
            <!-- Target package and directory -->
        </generator>
    </configuration>
</plugin>
```

### 2. Read & Write Geometry

After code generation, spatial columns in generated records will be typed as `org.locationtech.jts.geom.Geometry`:

```java
GeometryFactory gf = new GeometryFactory();

// Write (Insert / Update) — SRID is preserved in EWKB
Point point = gf.createPoint(new Coordinate(116.4074, 39.9042));
point.setSRID(4326);

dsl.insertInto(PLACES)
   .set(PLACES.ID, 1L)
   .set(PLACES.GEOM, point)
   .execute();

// Read (Select) — Returns JTS Geometry with database SRID
Geometry geom = dsl.select(PLACES.GEOM)
                   .from(PLACES)
                   .where(PLACES.ID.eq(1L))
                   .fetchOne(PLACES.GEOM);

System.out.println("SRID: " + geom.getSRID()); // 4326
System.out.println("WKT: " + geom.toText());   // POINT (116.4074 39.9042)
```

### 3. Spatial Queries & Transformations

For PostGIS spatial functions (such as `ST_Transform`, `ST_DWithin`, `ST_Distance`), use jOOQ's plain SQL templates:

```java
// Example: ST_Transform to EPSG:3857 in SQL
Field<Geometry> transformed = DSL.field(
    "ST_Transform({0}, 3857)", 
    PLACES.GEOM.getDataType(), 
    PLACES.GEOM
);

Geometry geom3857 = dsl.select(transformed)
                       .from(PLACES)
                       .where(PLACES.ID.eq(1L))
                       .fetchOne(transformed);
```

## License

[Apache License 2.0](LICENSE)
