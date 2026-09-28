# jooq-postgis

[English](README.md) | 中文说明

轻量级 PostGIS `geometry` 与 `geography` 类型的 jOOQ 自定义 Binding 实现 —— 在 PostgreSQL 空间字段与 JTS `Geometry` 对象之间提供无缝的双向类型转换。

## 特性

- **纯粹 JTS**：零 GeoTools 依赖，体积小、启动快、对 Maven Central 友好。
- **全类型支持**：完整支持 PostGIS 的 `geometry` 与 `geography` 两种空间类型。
- **原生 EWKB**：读写均保留空间数据的 SRID 坐标系和 2D / 3D 坐标维度。
- **格式高容错**：支持 JDBC 的 PGobject、十六进制（Hex）字符串以及二进制 byte[] 流。
- **预编译与批量安全**：针对 NULL 值与内联参数（`ParamType.INLINED`）优化了 SQL 渲染，杜绝 Postgres 类型推断异常。
- **开箱即用支持 jOOQ Codegen**：下游可通过 `jooq-codegen-maven` 一键生成 JTS Geometry 强类型字段。

## 环境要求

- Java 8+
- jOOQ 3.14+
- PostgreSQL 及 PostGIS 扩展
- JTS Core 1.18+

## Maven 依赖

在你的项目中添加以下依赖：

```xml
<dependency>
    <groupId>top.yunitytech.maven</groupId>
    <artifactId>jooq-postgis</artifactId>
    <version>1.0.1</version>
</dependency>
```

> **说明：** `org.jooq:jooq` 和 `org.postgresql:postgresql` 的范围为 `provided`，请确保您的应用提供了对应依赖。`org.locationtech.jts:jts-core` 已作为直接依赖自动引入。

## 使用方法

### 1. 配置 jOOQ 代码生成器（Codegen）

在下游项目的 `jooq-codegen-maven` 插件配置中，将 `jooq-postgis` 添加到插件的 `<dependencies>` 中，并配置 `<forcedTypes>`：

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
            <version>1.0.1</version>
        </dependency>
    </dependencies>
    <configuration>
        <generator>
            <database>
                <name>org.jooq.meta.postgres.PostgresDatabase</name>
                <!-- 配置 forcedTypes 映射空间字段 -->
                <forcedTypes>
                    <forcedType>
                        <userType>org.locationtech.jts.geom.Geometry</userType>
                        <binding>top.yunitytech.maven.jooq.binding.PostgisGeometryBinding</binding>
                        <!-- 自动匹配数据库中所有的 geometry 和 geography 字段 -->
                        <includeTypes>(?i:geometry|geography)</includeTypes>
                    </forcedType>
                </forcedTypes>
            </database>
            <!-- 配置生成包名与目录 -->
        </generator>
    </configuration>
</plugin>
```

### 2. 读取与写入几何对象

代码生成完成后，生成实体表中的空间字段将被自动强类型化为 `org.locationtech.jts.geom.Geometry`：

```java
GeometryFactory gf = new GeometryFactory();

// 写入（Insert / Update）—— SRID 和维度信息会完整保留在 EWKB 中写入数据库
Point point = gf.createPoint(new Coordinate(116.4074, 39.9042));
point.setSRID(4326);

dsl.insertInto(PLACES)
   .set(PLACES.ID, 1L)
   .set(PLACES.GEOM, point)
   .execute();

// 读取（Select）—— 返回带有数据库原生 SRID 的 JTS Geometry
Geometry geom = dsl.select(PLACES.GEOM)
                   .from(PLACES)
                   .where(PLACES.ID.eq(1L))
                   .fetchOne(PLACES.GEOM);

System.out.println("SRID: " + geom.getSRID()); // 4326
System.out.println("WKT: " + geom.toText());   // POINT (116.4074 39.9042)
```

### 3. 空间函数与坐标系转换

对于 PostGIS 空间函数（如 `ST_Transform` 坐标转换、`ST_DWithin` 范围查询、`ST_Distance` 距离计算），可直接通过 jOOQ 的 Plain SQL 模版调用：

```java
// 示例：在 SQL 层将几何对象重投影为 EPSG:3857
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

## 开源协议

[Apache License 2.0](LICENSE)
