# GeoSPARQL CRS Deployment

## Purpose

GraphDB GeoSPARQL uses Apache Jena and Apache SIS for CRS-aware exact evaluation. The plugin can evaluate projected CRS data, including easting/northing coordinates, without replacing the source geometry literal with CRS84 data. The result depends on the Apache SIS CRS data available to GraphDB.

This guide explains how to provide and test those CRS data files.

## Policy

- The default plugin artifact does not bundle the optional Apache SIS `sis-embedded-data` EPSG dataset or national
  grid-shift files.
- CRS84 index geometry is used only for Lucene candidate lookup.
- Exact evaluation uses the source geometry literal and the CRS operations available to Apache SIS.
- Missing CRS definitions or grid-shift files are a configuration problem. They are not permission to reinterpret coordinates as CRS84 or WGS84.
- A CRS that Apache SIS cannot parse, resolve, or transform fails by default. Missing EPSG or grid data can also reduce transformation accuracy without causing an error, so projected CRSes must be checked with known points.

## Mixed-CRS Candidate Lookup

Non-disjoint GeoSPARQL property relations use CRS84 index-envelope intersection for candidate lookup in both binding
directions. A local bound geometry can therefore select a geographic candidate subset when the indexed source
geometry literals use a different CRS. Broad or world-fallback index envelopes can still retain many candidates.
Exact evaluation preserves source CRS semantics and subject/object order while transforming the right operand into
the left operand's CRS when required.

This policy treats independently derived CRS84 index envelopes as conservative for non-disjoint candidate lookup,
including when Jena cleans transformed coordinates in a non-CRS84 target CRS. Transformed envelopes include Jena's
pinned cleanup displacement in CRS84 units. Apache SIS does not provide a universal mathematical proof that its
transformed envelopes contain every possible transformed geometry. This is an intentional engineering trade-off: the
plugin does not scan every different-CRS source merely because that formal proof is unavailable. A reproducible
under-bound remains a correctness defect.

Transform failures and results that cannot be represented safely as one Lucene geographic rectangle use the world
CRS84 fallback. CRS-environment fingerprint changes require a force reindex, and differential coverage across the
maintained CRS and runtime matrix is a correctness control. These safeguards do not turn the SIS result into a
mathematical enclosure guarantee.

Disjoint relations keep a separate mixed-CRS exact-evaluation phase. Candidate lookup does not use independently
derived CRS84 envelopes to make a definite disjoint classification when coordinate cleanup occurs in another CRS.
This phase can process all spatial sources in relevant different CRSes.

This asymmetry keeps ordinary non-disjoint queries selective while preventing independently derived envelopes from
establishing a positive disjoint result without exact evaluation.

## Default Behavior Without CRS Configuration

The plugin is intended to work out of the box for CRS84 GeoSPARQL data.

Default supported behavior:

- WKT literals without an explicit SRS URI use the GeoSPARQL default CRS, CRS84: `http://www.opengis.net/def/crs/OGC/1.3/CRS84`.
- WKT/GML literals that explicitly use CRS84 can be indexed and evaluated without extra Apache SIS data.
- CRS84-derived index geometry is written to Lucene for candidate lookup.
- Exact evaluation still uses the source geometry literal.

Other CRS support without configuration is inherited from Apache Jena and Apache SIS runtime defaults. Apache Jena documents that CRS conversion depends on the local Apache SIS EPSG dataset, and Apache SIS documents that without the EPSG geodetic dataset only a small CRS subset is available and coordinate operations may be less accurate or have unspecified domains of validity.

Only CRS84 is guaranteed without extra CRS data. Check every projected CRS, other EPSG CRS, national grid, and datum shift in the target GraphDB runtime before relying on it.

Useful upstream references:

- [Apache Jena GeoSPARQL](https://jena.apache.org/documentation/geosparql/)
- [Apache SIS EPSG dataset setup](https://sis.apache.org/epsg.html)

## Loading Data With Unsupported CRS

`ignoreErrors` is `false` by default.

When GeoSPARQL is enabled and repository data contains a geometry whose CRS cannot be parsed, resolved, or transformed with the available CRS data, indexing fails by default and reports the geometry and CRS. This can happen during incremental indexing after inserts or during a full reindex.

If `ignoreErrors=true`, invalid or unsupported repository geometries are skipped during indexing with a warning. This can help load incomplete data, but it does not make the skipped geometries queryable. `ignoreErrors` does not apply to Lucene index storage or writer failures; those abort the GraphDB transaction so a deletion cannot be published without its replacement documents.

Set the option with this SPARQL update:

```sparql
PREFIX plugin: <http://www.ontotext.com/plugins/geosparql#>

INSERT DATA {
  [] plugin:ignoreErrors true
}
```

After changing this option, [rebuild the GeoSPARQL index](../README.md#rebuilding-the-geosparql-index). Set the value to `false` and rebuild again to restore strict indexing.

`ignoreErrors` does not apply to query-supplied literals. If a SPARQL query supplies a geometry literal with an unsupported CRS, the query evaluation still fails rather than silently treating the coordinates as CRS84 or WGS84.

If the plugin is disabled while data is loaded, unsupported CRS failures are deferred until the plugin is enabled or the GeoSPARQL index is rebuilt.

## Startup Logging

The plugin does not validate arbitrary CRSes at startup and does not fail startup when `SIS_DATA` is missing. Startup logging is informational and reports only the Apache SIS data environment visible to GraphDB.

Expected startup messages:

```text
GeoSPARQL CRS data: SIS_DATA is not set. CRS84/default GeoSPARQL data is supported. Projected/EPSG CRS data may require Apache SIS data and grid files; unsupported CRS will fail indexing/query evaluation.
```

```text
GeoSPARQL CRS data: SIS_DATA=/opt/graphdb/sis-data is a readable directory. Projected/EPSG CRS support depends on the CRS definitions and grid files available in that directory. Unsupported CRS will fail indexing/query evaluation.
```

```text
GeoSPARQL CRS data: SIS_DATA=/opt/graphdb/sis-data is not readable. CRS84/default GeoSPARQL data is supported, but CRS data from SIS_DATA will not be available. Unsupported CRS will fail indexing/query evaluation.
```

The final message is logged as a warning when `SIS_DATA` is configured but unusable. The unset and readable cases are logged as informational messages.

## Required Runtime Configuration

GraphDB installations that use projected CRSes should provide an Apache SIS data directory and expose it with `SIS_DATA`.

### Prepare the EPSG database

The plugin includes the Apache Derby runtime, but it does not include the EPSG database. Apache SIS stores its local EPSG database under `Databases/SpatialMetadata` inside the `SIS_DATA` directory.

Use the [Apache SIS 1.6 EPSG setup](https://sis.apache.org/epsg.html) to create the directory:

1. Download and unpack the Apache SIS 1.6 binary bundle.
2. Run `bin/sis crs EPSG:3006`, or another EPSG code that you need.
3. Accept the EPSG terms when Apache SIS prompts on first use.
4. Confirm that the generated `data/Databases/SpatialMetadata` directory exists.
5. Copy or mount the complete `data` directory for GraphDB and set `SIS_DATA` to that directory.

If your organisation already maintains an Apache SIS data directory, use that instead and validate the CRSes you need.

### Expose the directory to GraphDB

Minimum requirements:

- `SIS_DATA` points to an Apache SIS data directory that the GraphDB process can access.
- The directory contains `Databases/SpatialMetadata` with the required EPSG definitions and coordinate operations.
- Any required datum-shift grid files are present.
- The Apache SIS, EPSG, and grid-file versions are recorded with the GraphDB and plugin versions.

Container example:

```bash
docker run \
  -e SIS_DATA=/opt/graphdb/sis-data \
  -v /srv/graphdb/sis-data:/opt/graphdb/sis-data \
  graphdb-with-geosparql
```

Systemd example:

```ini
[Service]
Environment=SIS_DATA=/var/lib/graphdb/sis-data
```

The exact path is up to you. It must be visible to the JVM that runs GraphDB. Do not mount the Derby database read-only unless it has been prepared for read-only use and Derby has a writable temporary directory.

## CRS Data Contents

The required data depends on the CRSes you need to support.

Typical contents:

- EPSG CRS definitions and coordinate operation metadata.
- National or regional grid-shift files required by those coordinate operations.
- Any approved Apache SIS data files needed to resolve and transform each required CRS.

Examples:

- CRSes outside the subset built into Apache SIS, such as `EPSG:3006`, require the external EPSG database.
- British National Grid support such as `EPSG:27700` may require OSGB grid-shift files such as `OSTN15_NTv2_OSGBtoETRS.gsb` for high-precision datum transformations.

Do not commit licensed CRS datasets or grid files into this repository unless their license and redistribution status have been explicitly approved.

## Validation

The plugin does not run these checks at startup. Test every projected CRS in the target GraphDB runtime before relying on it.

Recommended validation checks:

- Resolve every CRS that the GraphDB installation must support.
- Transform at least one known point from a trusted source for each supported projected CRS.
- Compare transformed coordinates against the expected values within the required tolerance.
- Run at least one query using a GeoSPARQL property relation for each supported projected CRS.
- Confirm Lucene returns candidates and exact evaluation uses the source geometry literal.
- Confirm a CRS that cannot be resolved produces a clear error.
- Use known-point checks to detect missing operation or grid data that reduces accuracy without causing an error.

### Repository packaging smoke test

The repository includes an opt-in smoke test that packages the plugin and supplies an external `SIS_DATA` directory. It runs a CRS84 GeoSPARQL property relation, an indexed EPSG:3006 GeoSPARQL property relation, and an EPSG:3006 five-metre distance filter function in GraphDB:

```bash
mvn -Pgraphdb-packaging-smoke verify
```

This test intentionally uses GraphDB 10.8.12 because GraphDB 10.8.x is the last generation that can start in unattended public CI without provisioning a separately issued licence; GraphDB 11+ requires a registered licence even for the Free edition. EPSG:3006 is outside the subset built into Apache SIS, so the test fails if the external EPSG database is missing. The smoke test checks assembled-plugin packaging and `SIS_DATA` wiring; it does not establish GraphDB 10.8 as the production target or replace testing in the target GraphDB 11 runtime.

### Target runtime check

The repository tests use these EPSG:3006 points:

```text
CRS:      http://www.opengis.net/def/crs/EPSG/0/3006
Point 1:  POINT Z (522000 6704000 100)
Point 2:  POINT Z (522003 6704004 100)
Expected distance: 5 metres, within 0.01 metre
```

Run this query against the target GraphDB runtime. It returns `true` when GraphDB loads EPSG:3006 from the external EPSG database and evaluates the distance in metres:

```sparql
PREFIX geo: <http://www.opengis.net/ont/geosparql#>
PREFIX geof: <http://www.opengis.net/def/function/geosparql/>
PREFIX uom: <http://www.opengis.net/def/uom/OGC/1.0/>

ASK {
  BIND(geof:distance(
    "<http://www.opengis.net/def/crs/EPSG/0/3006> POINT Z (522000 6704000 100)"^^geo:wktLiteral,
    "<http://www.opengis.net/def/crs/EPSG/0/3006> POINT Z (522003 6704004 100)"^^geo:wktLiteral,
    uom:metre
  ) AS ?distance)
  FILTER(abs(?distance - 5.0) < 0.01)
}
```

This query checks that GraphDB can load the EPSG:3006 definition and use its metre unit. It does not check transformation to another CRS or the accuracy of grid-shift data.

Also test an indexed GeoSPARQL property relation in a temporary repository. Insert the following data, enable GeoSPARQL, and run the `ASK` query. The expected result is `true`.

```sparql
PREFIX geo: <http://www.opengis.net/ont/geosparql#>
PREFIX ex: <http://example.com/crs-check/>

INSERT DATA {
  ex:container a geo:Feature ;
    geo:hasDefaultGeometry ex:containerGeometry .
  ex:containerGeometry a geo:Geometry ;
    geo:asWKT "<http://www.opengis.net/def/crs/EPSG/0/3006> POLYGON((521990 6703990,521990 6704010,522010 6704010,522010 6703990,521990 6703990))"^^geo:wktLiteral .
  ex:thing a geo:Feature ;
    geo:hasDefaultGeometry ex:thingGeometry .
  ex:thingGeometry a geo:Geometry ;
    geo:asWKT "<http://www.opengis.net/def/crs/EPSG/0/3006> POINT(522000 6704000)"^^geo:wktLiteral .
}
```

```sparql
PREFIX plugin: <http://www.ontotext.com/plugins/geosparql#>

INSERT DATA {
  [] plugin:enabled true
}
```

```sparql
PREFIX geo: <http://www.opengis.net/ont/geosparql#>
PREFIX ex: <http://example.com/crs-check/>

ASK {
  ex:thing geo:sfWithin ex:container
}
```

For `EPSG:27700`, use an OSGB known point from a trusted source and check that the required NTv2 grid file is available. Get the expected value from the authority or data provider chosen for the installation.

## Troubleshooting

If logs contain:

```text
The "SIS_DATA" environment variable is not set.
```

then GraphDB is running without an explicit Apache SIS data directory. CRS84 data still works, but do not assume that projected CRSes work until you test them.

If logs contain warnings like:

```text
Cannot find NTv2 file named "OSTN15_NTv2_OSGBtoETRS.gsb".
Cannot find NTv2 file named "OSTN02_NTv2.gsb".
```

then a CRS operation needs a grid-shift file that is not present in the deployed CRS data. Install the required file, restart GraphDB, and rerun known-point validation.

If a geometry fails with an unsupported CRS error, treat it as one of:

- The CRS URI is not supported by Jena/SIS.
- Jena/SIS supports the CRS, but the SIS data installed for GraphDB is missing.
- The geometry coordinates are outside the CRS domain.
- The literal is invalid or uses an unsupported datatype.

Do not fix these failures by stripping CRS URIs or converting source geometry literals to CRS84 at import time. CRS84 transformation is only acceptable for derived index geometry.

## Release Checklist

- For projected CRS deployments, `SIS_DATA` is set in the target runtime.
- Required CRS definitions are present for every CRS the installation must support.
- Required grid-shift files are present for every CRS that needs them.
- CRS data licenses and redistribution constraints are approved.
- Known-point validation passes for every projected CRS the installation must support.
- GeoSPARQL property relation and filter function smoke tests pass.
- An unresolved CRS produces a clear error.
- Known-point checks catch missing data that reduces transformation accuracy.
- The GeoSPARQL index is [rebuilt](../README.md#rebuilding-the-geosparql-index) after CRS data changes affect index geometry.
