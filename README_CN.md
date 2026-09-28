# jooq-postgis

[English](README.md) | 中文说明

生产级、轻量化的 PostGIS 空间数据类型 jOOQ Binding 实现 —— 在 PostgreSQL PostGIS 空间字段与 JTS `org.locationtech.jts.geom.Geometry` 之间提供无缝、高性能的双向类型转换。

专为使用 `jooq-codegen-maven` 自动生成强类型 JTS 空间字段的项目设计，零臃肿外部依赖。

## 核心特性

- **纯粹 JTS（零 GeoTools 依赖）**：彻底剔除 `gt-main` 与 OSGeo 仓库依赖，轻量快速、对 Maven Central 规范 100% 友好。
- **geometry 与 geography 独立语义绑定**：
  - `PostgisGeometryBinding`：专用于 `geometry` 字段（笛卡尔/平面几何），使用 `PGobject(type="geometry")` 绑定，生成 SQL 为 `?::geometry`。
  - `PostgisGeographyBinding`：专用于 `geography` 字段（大地/椭球球面几何），使用 `PGobject(type="geography")` 绑定，生成 SQL 为 `?::geography`。
  - 杜绝平面与球面语义混淆，原生支持 PostGIS 空间计算（`ST_Distance`、`ST_DWithin`、`ST_Intersects`），无需在 SQL 层做冗余的类型强转。
- **全维度坐标支持（2D / 3D / 3DM / 4D）**：
  - **2D (XY)**：常规平面空间数据。
  - **3D (XYZ)**：包含高程/高度的 3 维坐标数据。
  - **3DM (XYM)**：包含测量值（Measure）的 3 维数据（如 GPS 时间戳、动态校准值）。
  - **4D (XYZM)**：遥感卫星轨道覆盖、4D 观测足迹（如 `POINT ZM`）。
- **原生 JDBC PGobject 安全绑定**：
  - 使用 `PGobject` 显式声明数据类型，杜绝 `setString()` 在某些 PostgreSQL JDBC 驱动版本中出现的类型推断歧义。
  - 高容错反序列化解析器，同时兼容十六进制 EWKB Hex、PostGIS 原生 EWKT（`SRID=...;...`）、标准 WKT 以及二进制 `byte[]` 数据流。
- **PreparedStatement 与批量写入安全**：严谨处理 NULL 值（使用 `Types.OTHER` 并传对应空间类型名）和内联参数渲染（`ParamType.INLINED`）。

## 环境要求

- Java 8+
- jOOQ 3.14+
- PostgreSQL 及 PostGIS 扩展
- JTS Core 1.18+

## Maven 依赖

在你的项目 `pom.xml` 中引入：

```xml
<dependency>
    <groupId>top.yunitytech.maven</groupId>
    <artifactId>jooq-postgis</artifactId>
    <version>1.0.4</version>
</dependency>
```

> **说明：** `org.jooq:jooq` 和 `org.postgresql:postgresql` 的范围为 `provided`，由您的上层应用根据实际需求提供具体版本。`org.locationtech.jts:jts-core` 已作为直接运行时依赖自动引入。

## 使用方法

### 1. 配置 jOOQ 代码生成器（`jooq-codegen-maven`）

在代码生成插件配置中，引入 `jooq-postgis` 并配置 `<forcedTypes>`：

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
                    <!-- 平面几何 geometry 字段绑定 -->
                    <forcedType>
                        <userType>org.locationtech.jts.geom.Geometry</userType>
                        <binding>top.yunitytech.maven.jooq.binding.PostgisGeometryBinding</binding>
                        <includeTypes>(?i:geometry)</includeTypes>
                    </forcedType>
                    <!-- 球面地理 geography 字段绑定 -->
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

### 2. 读取与写入几何对象

代码生成完成后，生成实体表中的空间字段将被自动强类型化为 `org.locationtech.jts.geom.Geometry`：

```java
GeometryFactory gf = new GeometryFactory();

// 1. 写入 2D 几何对象（自动附带 SRID）
Point beijing = gf.createPoint(new Coordinate(116.4074, 39.9042));
beijing.setSRID(4326);

dsl.insertInto(SPATIAL_RECORD)
   .set(SPATIAL_RECORD.ID, 1L)
   .set(SPATIAL_RECORD.GEOM, beijing)
   .execute();

// 2. 写入 4D 几何对象（XYZM —— 如遥感卫星轨迹观测点）
Point satelliteObs = gf.createPoint(new CoordinateXYZM(116.4, 39.9, 500000.0, 1695888000.0));
satelliteObs.setSRID(4326);

dsl.insertInto(SPATIAL_RECORD)
   .set(SPATIAL_RECORD.ID, 2L)
   .set(SPATIAL_RECORD.GEOM, satelliteObs)
   .execute();

// 3. 读取几何对象（完整保留 Z 高程与 M 测量维度）
Geometry result = dsl.select(SPATIAL_RECORD.GEOM)
                     .from(SPATIAL_RECORD)
                     .where(SPATIAL_RECORD.ID.eq(2L))
                     .fetchOne(SPATIAL_RECORD.GEOM);

Coordinate coord = result.getCoordinate();
System.out.println("高程 Z: " + coord.getZ()); // 500000.0
System.out.println("观测 M: " + coord.getM()); // 1695888000.0
```

### 3. 原生 Geography 球面距离与范围查询

由于 `PostgisGeographyBinding` 直接对齐 PostGIS 的 `geography` 类型，在大地球面上的距离计算直接以米为单位，且不需要类型强转：

```java
// 原生计算两点大地球面距离（单位：米）
Double distanceMeters = dsl.select(
        DSL.field("ST_Distance({0}, {1})", Double.class, A.GEOG, B.GEOG)
    ).from(A).crossJoin(B)
    .fetchOne(0, Double.class);

// 原生判断两点球面距离是否在 200 公里范围内
Boolean isNearby = dsl.select(
        DSL.field("ST_DWithin({0}, {1}, 200000)", Boolean.class, A.GEOG, B.GEOG)
    ).from(A).crossJoin(B)
    .fetchOne(0, Boolean.class);
```

### 4. 独立空间编解码器（`PostgisCodec`）

如果您需要在 jOOQ 体系之外独立编解码 PostGIS 空间数据（例如常规 JDBC 查询、REST 接口序列化、MQ 消息消费等），可直接调用 `PostgisCodec`：

```java
// 反序列化 PGobject、EWKB Hex、EWKT 或 byte[] 为 JTS Geometry
Geometry geom = PostgisCodec.from(databaseObject);

// 将 JTS Geometry 转换为 PostGIS 最佳持久化表示（2D/3D 转 EWKB Hex，3DM/4D 转 EWKT）
String repr = PostgisCodec.toSpatialRepresentation(geom);
```

## 开源协议

[Apache License 2.0](LICENSE)
