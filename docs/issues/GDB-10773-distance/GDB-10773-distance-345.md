# GDB-10773 — distance 3–4–5 (EPSG:3006)

- **Jira:** https://graphwise.atlassian.net/browse/GDB-10773
- **Source:** Lars Wikström (Triona); note by Vladimir Alexiev
- **Data:** [`GDB-10773-distance-345.ttl`](./GDB-10773-distance-345.ttl)

## Case

Two points in engineering CRS **EPSG:3006** (SWEREF99 TM), same Z:

| Point | Easting | Northing |
|-------|---------|----------|
| `data:obj3` | 522000.0 | 6704000.0 |
| `data:obj4` | 522003.0 | 6704004.0 |

ΔE = **3 m**, ΔN = **4 m** → by Pythagoras the planar distance is **5 m**.

## Query

```sparql
PREFIX geo: <http://www.opengis.net/ont/geosparql#>
PREFIX geof: <http://www.opengis.net/def/function/geosparql/>
PREFIX uom: <http://www.opengis.net/def/uom/OGC/1.0/>
PREFIX data: <http://www.triona.se/data#>

SELECT * WHERE {
  data:obj3 geo:hasGeometry/geo:asWKT ?geom1 .
  data:obj4 geo:hasGeometry/geo:asWKT ?geom2 .
  BIND(geof:distance(?geom1, ?geom2, uom:metre) AS ?distance)
}
```

## Expected vs actual (v1)

| | Value |
|---|---|
| **Expected** | `5` (metres) |
| **Actual (GraphDB v1)** | `3.237365138073413` |

Failure mode: geometries coerced to WGS 84 before metric ops ([GDB-9428](https://graphwise.atlassian.net/browse/GDB-9428)).

## Pass criterion

`?distance` ≈ **5.0** (tight tolerance, e.g. within centimetres) when distance is evaluated in EPSG:3006, not after WGS84 projection.
