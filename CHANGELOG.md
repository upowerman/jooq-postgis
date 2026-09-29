# Changelog

## Unreleased

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
