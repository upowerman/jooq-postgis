# jooq-postgis

[English](README.md) | 中文说明

[![Maven Central](https://img.shields.io/maven-central/v/top.yunitytech.maven/jooq-postgis.svg?color=brightgreen)](https://central.sonatype.com/artifact/top.yunitytech.maven/jooq-postgis)
[![CI](https://github.com/upowerman/jooq-postgis/actions/workflows/ci.yml/badge.svg)](https://github.com/upowerman/jooq-postgis/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-8%2B-orange.svg?logo=openjdk&logoColor=white)](https://www.oracle.com/java/)
[![jOOQ](https://img.shields.io/badge/jOOQ-3.14%2B-008080.svg)](https://www.jooq.org/)
[![PostGIS](https://img.shields.io/badge/PostGIS-3.x-275B89.svg?logo=postgresql&logoColor=white)](https://postgis.net/)
[![JTS](https://img.shields.io/badge/JTS-1.18%2B-green.svg)](https://github.com/locationtech/jts)

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

- Java 8+（以 `--release 8` 编译）
- jOOQ 3.14+（运行时；jOOQ 3.15+ 上做代码生成需为每个 `<forcedType>` 追加 `<genericBinding>true</genericBinding>`，见下文）
- PostgreSQL 及 PostGIS 扩展（仅支持 PostgreSQL/PostGIS 方言）
- JTS Core 1.18+

## Maven 依赖

在你的项目 `pom.xml` 中引入：

```xml
<dependency>
    <groupId>top.yunitytech.maven</groupId>
    <artifactId>jooq-postgis</artifactId>
    <version>1.0.6</version>
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
            <version>1.0.6</version>
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

> **jOOQ 3.15+ 注意：** jOOQ 3.15 起原生引入了空间类型支持，代码生成器会将 `geometry`
> 列解析为 `org.jooq.Geometry` 而非 `OTHER`。在 jOOQ 3.15+（含 3.19.x）上，请为上方**两个**
> `<forcedType>` 都追加 `<genericBinding>true</genericBinding>` —— 绑定类将通过其
> `(Class<T>, Class<U>)` 构造器实例化，生成的代码才能通过编译。jOOQ 3.14 上该元素不存在，必须省略。

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

// 将 JTS Geometry 转换为 PostGIS 持久化表示（全维度统一输出大端 EWKB Hex：
// XY / XYZ / XYM / XYZM，SRID 非零时内嵌）
String repr = PostgisCodec.toSpatialRepresentation(geom);
```

## 正确性与边界语义

编解码器的维度处理尽可能对齐 PostgreSQL / PostGIS 语义：

- **NaN 是合法坐标值，不是维度信号。** `LINESTRING Z(0 0 NaN, 1 1 5)`（PostGIS 接受存储，`ST_NDims = 3`）可无损往返：同一几何内任一坐标携带非 NaN 值即视为该维度存在。
- **类型化 JTS 序列优先。** 以 `CoordinateXYM` / `CoordinateXYZM`（或带 measures 的 packed 序列）构建的几何，即使 M 值全为 NaN 也保留其声明维度；普通 `Coordinate` 序列按坐标值判定。
- **集合维度强约束。** `GEOMETRYCOLLECTION(POINT(1 2), POINT Z(3 4 5))` 会抛出 `IllegalArgumentException` 拒绝，与 PostGIS 行为一致（`Dimensions mismatch in lwcollection`）。
- **外来（非 PostGIS）WKB 按叶子标志解码。** 部分 ISO WKB 写出器在集合头部少写维度标志。`fromWkb` 对每个叶子几何的 Z/M 标志建档（JTS 本身按各元素自身标志解析），因此 2D 集合头下的 `POINT M` 子元素会正确解码为 XYM，而不会把 M 静默误读为 Z。同一字节流中叶子标志必须一致。
- **EMPTY 几何以 2D EWKB 序列化；写入 Z/M/ZM typmod 列会被服务端拒绝。** JTS 无法表达"POINT Z EMPTY"这类带维度的空几何——空几何不携带维度信息，因此一律按 2D 序列化，PostgreSQL 将报 `Column has Z dimension but geometry does not`。读取不受影响：PostGIS 写入的带维度空几何可正常解码为空 JTS 几何。若已知目标列类型，可显式声明维度：
  ```java
  String hex = PostgisCodec.toSpatialRepresentation(emptyPoint, DimensionAnalyzer.CoordinateDimension.XYZ); // "POINT Z EMPTY"
  ```
- **仅支持简单 OGC 几何类型。** Point / LineString / Polygon / Multi* / GeometryCollection（WKB 类型 1–7）映射为 JTS 类型。曲线/曲面类型（`CIRCULARSTRING`、`COMPOUNDCURVE`、`CURVEPOLYGON`、`TRIANGLE`、`POLYHEDRALSURFACE` 等）JTS 无法表示，读取时抛异常——此类列在 codegen 后仍声明为 `Geometry`，凡取到含此类值的行都会抛错。请在 codegen 中按名称排除相关表/列（`<excludes>`）。
- **集合不允许混合非零 SRID。** EWKB 只内嵌根几何的 SRID；若非空子元素声明了不同的非零 SRID，将抛出 `IllegalArgumentException` 拒绝（而非静默丢弃）。SRID 为 0（未设置）的子元素继承根几何的 SRID。
- **typmod 与 SRID 由 PostgreSQL 强制。** `geometry(Point,4326)` 约束与 SRID 不匹配由服务端拒绝——用户构建的几何请务必调用 `setSRID()`。Binding 本身只负责传输。
- **仅支持 PostgreSQL / PostGIS。** 无论配置何种 SQL 方言都会渲染 `?::geometry` / `?::geography` 强转，不支持其他数据库。
- **更严格的 codegen 匹配（可选）。** 若库中存在名称含 `geometry`/`geography` 的自定义类型，可使用锚定正则（如 `(?i:^geometry$)`）。

## 测试与 CI

集成测试连接真实 PostgreSQL + PostGIS 运行，不可达时自动跳过：

```bash
docker run --name postgis-test -e POSTGRES_PASSWORD=postgres -p 5432:5432 -d postgis/postgis:16-3.4
docker exec postgis-test createdb -U postgres test_db
mvn test
```

连接配置按优先级取系统属性（`test.db.url`、`test.db.user`、`test.db.password`）或环境变量（`TEST_DB_URL`、`TEST_DB_USER`、`TEST_DB_PASSWORD`），默认 `localhost:5432/test_db`（postgres/postgres）。

CI（GitHub Actions）在每次 push / pull request 运行完整矩阵：JDK 17/21 × jOOQ 3.14.16/3.19.10，数据库为 `postgis/postgis:16-3.4` 服务容器。

本地使用 `-Djooq.version=...` 针对 jOOQ 3.16+ 测试时，请追加 `-Dmaven.compiler.release=17`：jOOQ 3.16+ 运行时要求 Java 17+，且其反应式 API 签名（`java.util.concurrent.Flow`）对 `--release 8` 编译不可见。发布的库本体仍为 Java 8 字节码。

## 开源协议

[Apache License 2.0](LICENSE)
