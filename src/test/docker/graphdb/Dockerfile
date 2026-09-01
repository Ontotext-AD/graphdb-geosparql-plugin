ARG JAVA_IMAGE=eclipse-temurin:21.0.11_10-jdk-jammy
ARG GRAPHDB_IMAGE=ontotext/graphdb:10.8.12

FROM ${JAVA_IMAGE} AS plugin-build

WORKDIR /plugin
COPY geosparql-plugin-graphdb-plugin.zip /tmp/geosparql-plugin.zip
RUN jar -xf /tmp/geosparql-plugin.zip

FROM ${GRAPHDB_IMAGE}

USER root

# GraphDB 10.8.12 supplies Java 11, while the plugin's Jena 6 dependencies require Java 21.
COPY --from=plugin-build /opt/java/openjdk /opt/java/openjdk

RUN rm -rf \
  /opt/graphdb/dist/lib/plugins/geosparql-plugin \
  /opt/graphdb/dist/lib/plugins/graphdb-geosparql-plugin

COPY --from=plugin-build /plugin/geosparql-plugin \
  /opt/graphdb/dist/lib/plugins/geosparql-plugin

# Optional EPSG data is deliberately not part of the assembled plugin. The smoke image
# supplies only SIS_DATA; the Derby runtime must come from the assembled plugin.
COPY sis-data/SIS-DATA/Databases/SpatialMetadata/ \
  /opt/graphdb/sis-data/Databases/SpatialMetadata/

ENV SIS_DATA=/opt/graphdb/sis-data
