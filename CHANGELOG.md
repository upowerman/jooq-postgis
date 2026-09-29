# Changelog

## 1.0.8 (2026-09-29)

### Fixed

- **EWKB serialization of empty `Polygon` components in collections:**
  Empty polygons now serialize with `numRings = 0` (`004000000300000000`) instead of attempting to access a null exterior ring, preventing `ERROR: Polygon must have at least four points in each ring` in PostgreSQL/PostGIS.
- **jOOQ 3.15+ genericBinding contract support:**
  Preserve `databaseType` from jOOQ codegen in `AbstractPostgisBinding` and dynamic generic `SpatialConverter<T>`. When columns map to `org.jooq.Geometry` / `org.jooq.Geography`, values convert without `ClassCastException` in generated Record getters and setters.
- **Strict input validation:**
  Explicitly resolve `org.jooq.Spatial` objects via reflection; unknown non-null objects passed to `PostgisCodec.from` throw `IllegalArgumentException` instead of silently returning `null`.
- **WkbScanner signed integer overflow defense:**
  Explicitly reject negative coordinate counts in linestrings, negative ring counts in polygons, and negative child counts in collections with descriptive `Malformed WKB/EWKB input` errors.
- **Hex EWKB boundary detection:**
  `isHex` requires a minimum length of 10 characters (5 bytes: byte order + geometry type), preventing short strings like `"00"` or `"01"` from false-positive detection.

### Added

- **ThreadLocal cleanup API:**
  Added `SpatialWkbPool.clear()` to allow explicitly removing thread-local `WKBReader`, `WKBWriter`, and `WKTReader` instances in managed environments (servlet redeployment, virtual threads, or thread pools).

### Performance

- **Pre-compiled regex pattern:**
  `fixWktEmptySpacing` now uses a static pre-compiled `WKT_EMPTY_SPACING_PATTERN` instead of recompiling regex on every invocation.

### Dependencies & Compatibility

- **pgjdbc 42.7.4 → 42.7.13, assertj-core 3.25.3 → 3.27.7** (closes all four high-severity
  Dependabot alerts): pgjdbc — CVE-2025-49146 (channelBinding=require fallback, fixed 42.7.7),
  CVE-2026-42198 (SCRAM PBKDF2 client DoS, fixed 42.7.11), CVE-2026-54291 (channel-binding
  authentication downgrade, fixed 42.7.12; affects 42.7.4–42.7.11); assertj — CVE-2026-24400
  (`isXmlEqualTo` XXE, fixed 3.27.7). Both are provided/test scope; the published artifact's
  behavior is unchanged.
- **Known ecosystem constraint (documented, not fixable here):** pgjdbc 42.7.5+ reports
  JDBC-spec-compliant uppercase metadata column labels, which jOOQ reads case-sensitively —
  code generation fails for every table on jOOQ ≤ 3.19 ([jOOQ #17873](https://github.com/jOOQ/jOOQ/issues/17873),
  fixed in jOOQ 3.20.0 only). The codegen integration test now aborts with an explanatory
  message on that combination; CI gained a jOOQ 3.20 leg where codegen is fully verified.
  **Users running codegen on jOOQ ≤ 3.19 should pin org.postgresql:postgresql to 42.7.4;
  runtime usage of this library is unaffected by the driver version.**

## 1.0.7 (2026-09-29)

### Fixed

- **EMPTY geometries into Z/M/ZM-typmod columns: documented the server rejection and added an
  explicit-dimension workaround.** JTS cannot represent typed empties, so empties serialize as 2D
  and PostgreSQL rejects them for dimension-typmod columns (`Column has Z dimension but geometry
  does not`). New overload
  `PostgisCodec.toSpatialRepresentation(Geometry, DimensionAnalyzer.CoordinateDimension)` emits a
  dimensioned empty (e.g. "POINT Z EMPTY") when the column type is known; for non-empty geometries
  the requested dimension must match the data. Reading was already unaffected.
- **EWKT with a non-numeric SRID (`SRID=abc;...`) now fails with a contextual `ParseException`**
  instead of leaking a raw `NumberFormatException`.
- **WKB parsing is strict about stream length and counts.** Trailing bytes after a complete
  geometry and negative ring/child counts are rejected by the `WkbScanner` with clear
  `Malformed WKB/EWKB input` messages instead of being silently ignored or delegated to JTS with
  cryptic errors.
- **Collections with conflicting non-zero component SRIDs are rejected on write.** EWKB embeds
  only the root SRID, so a non-empty component declaring a different non-zero SRID would
  previously be silently lost; it now throws `IllegalArgumentException` (SRID-0 components are
  "unset" and inherit the root's SRID, as before). Empty components are exempt.
- Guarded the built-in EWKB writer against empty `Polygon`s (null exterior ring) when writing
  dimensioned empties.

### Added

- Packaging metadata: `Automatic-Module-Name: top.yunitytech.maven.jooq.binding` manifest entry
  for stable module-path usage; standard `scm:git:` connection URLs; project name spelling
  ("jOOQ-PostGIS").

## 1.0.6 (2026-09-28)

### Fixed

- **Foreign WKB with under-declared collection headers no longer corrupts M values into Z.**
  `fromWkb` now profiles the Z/M flags of every leaf geometry in the byte stream
  (non-PostGIS ISO writers may omit dimension flags on collection headers while JTS
  parses each element by its own flags), so `POINT M` children under a 2D header decode
  correctly to XYM / XYZM instead of silently re-interpreting M as Z. Leaf flags must be
  uniform — heterogeneous input is rejected as mixed-dimension, matching PostGIS.
- **NaN ordinates are legal PostGIS values and no longer fail reads.**
  `LINESTRING Z(0 0 NaN, 1 1 5)` (stored by PostGIS with `ST_NDims = 3`) previously threw
  `IllegalArgumentException: Mixed-dimension` on fetch. Dimension presence is now resolved
  with OR semantics per coordinate sequence, and typed `CoordinateXYM` / `CoordinateXYZM`
  sequences keep their declared dimension even when all M values are NaN.

### Changed

- All coordinate dimensions (including 3DM / 4D) now serialize as big-endian EWKB Hex via
  a built-in writer — previously XYM / XYZM used EWKT text. JTS `WKBWriter` cannot emit the
  M flag, and `WKTWriter(4)` silently drops the M marker when all M values are NaN.
- Collections are dimension-strict like PostGIS (`Dimensions mismatch in lwcollection`);
  within a single non-collection geometry, partial-NaN ordinate patterns are no longer
  treated as "mixed dimension".
- Malformed / truncated WKB fails fast with a clear `Malformed WKB/EWKB input` message;
  parse-exception messages summarize the input instead of embedding it in full.
- Removed the legacy static forwarders on `AbstractPostgisBinding`
  (`toSpatialRepresentation`, `fixWktEmptySpacing`, `validateDimensionConsistency`, `isHex`,
  `DimensionFilter`, `GEOMETRY_FACTORY`, `PACKED_GEOMETRY_FACTORY`), the deprecated
  `PostgisCodec.DimensionFilter` alias, and the now-unused `SpatialWkbPool.getWktWriter4D()` —
  all dead since the 1.0.4 codec split. Use `PostgisCodec` as the single codec entry point.

### Added

- Generic binding types (`PostgisGeometryBinding<T, U>` / `PostgisGeographyBinding<T, U>`)
  implementing the jOOQ 3.15+ `<genericBinding>` instantiation protocol
  (`new Binding<T, U>(Class<T>, Class<U>)`), while raw instantiation for jOOQ 3.14
  code generation remains fully supported.
- GitHub Actions CI: JDK 17/21 × jOOQ 3.14.16/3.19.10 matrix against a PostGIS 3.4
  service container, running the full suite including integration tests.
- Edge-case test suites: foreign / ISO / big-endian WKB, NaN ordinate preservation,
  per-type dimension round-trips (LineString / MultiPoint / MultiLineString), 50,000-vertex
  geometries; integration tests for UPDATE statements, geography Z/M round-trips, NaN-Z
  data through the database, EMPTY components inside dimensioned collections, and
  CallableStatement OUT parameters.
- Integration test database connection configurable via `test.db.*` system properties or
  `TEST_DB_*` environment variables.

## 1.0.4 (2026-09)

- Extracted `PostgisCodec` from `AbstractPostgisBinding`; fixed `ParamType.INLINED` SQL
  rendering (delegated to jOOQ literal escaping) and verified codegen forcedType support.

## 1.0.3 (2026-09)

- Full coordinate dimension support (XY / XYZ / XYM / XYZM); separate
  `PostgisGeometryBinding` / `PostgisGeographyBinding`.

## 1.0.2 (2026-09)

- Prepared for the first Maven Central release.
