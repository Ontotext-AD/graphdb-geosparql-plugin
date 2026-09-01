package com.ontotext.trree.geosparql.lucene;

import com.ontotext.trree.geosparql.jena.SourceGeometryLiteral;
import com.ontotext.trree.sdk.PluginException;
import org.apache.jena.geosparql.implementation.GeometryWrapper;
import org.apache.jena.geosparql.implementation.jts.CoordinateSequenceDimensions;
import org.apache.jena.geosparql.implementation.jts.CustomCoordinateSequence;
import org.apache.jena.geosparql.implementation.jts.CustomGeometryFactory;
import org.apache.jena.geosparql.implementation.registry.SRSRegistry;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;
import org.locationtech.jts.geom.util.GeometryEditor;
import org.locationtech.jts.io.ByteOrderValues;
import org.locationtech.jts.io.Ordinate;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;

import java.util.EnumSet;

/**
 * Encodes the complete native-CRS source geometry used to reconstruct Jena exact-evaluation input.
 * Axis-order conversion preserves vertical and measure ordinates.
 */
final class SourceGeometryWkbCodec {
	private static final GeometryFactory WKB_WRITE_GEOMETRY_FACTORY = new GeometryFactory(
			new PrecisionModel(), 0, PackedCoordinateSequenceFactory.DOUBLE_FACTORY);

	private SourceGeometryWkbCodec() {
	}

	static byte[] encode(SourceGeometryLiteral source) {
		GeometryWrapper wrapper = source.asGeometryWrapper();
		CoordinateSequenceDimensions dimensions = wrapper.getCoordinateSequenceDimensions();
		Geometry geometry = xyGeometryPreservingOrdinates(wrapper);
		EnumSet<Ordinate> ordinates = switch (dimensions) {
			case XY -> Ordinate.createXY();
			case XYZ -> Ordinate.createXYZ();
			case XYM -> Ordinate.createXYM();
			case XYZM -> Ordinate.createXYZM();
		};
		WKBWriter writer = new WKBWriter(ordinates.size(), ByteOrderValues.LITTLE_ENDIAN, false);
		writer.setOutputOrdinates(ordinates);
		return writer.write(dimensions == CoordinateSequenceDimensions.XYM
				? repackXymForWkb(geometry) : geometry);
	}

	private static Geometry xyGeometryPreservingOrdinates(GeometryWrapper wrapper) {
		Geometry geometry = wrapper.getParsingGeometry().copy();
		if (SRSRegistry.getAxisXY(wrapper.getSrsURI())) {
			return geometry;
		}
		geometry.apply(new CoordinateSequenceFilter() {
			@Override
			public void filter(CoordinateSequence sequence, int index) {
				double x = sequence.getX(index);
				sequence.setOrdinate(index, 0, sequence.getY(index));
				sequence.setOrdinate(index, 1, x);
			}

			@Override
			public boolean isDone() {
				return false;
			}

			@Override
			public boolean isGeometryChanged() {
				return true;
			}
		});
		return geometry;
	}

	private static Geometry repackXymForWkb(Geometry geometry) {
		// JTS writes the third WKB ordinate from ordinal 2, while Jena's XYM sequence reserves ordinal 2 for Z
		// and exposes M separately. Repack only XYM so the WKB M value occupies the expected ordinal.
		GeometryEditor editor = new GeometryEditor(WKB_WRITE_GEOMETRY_FACTORY);
		return editor.edit(geometry, new GeometryEditor.CoordinateSequenceOperation() {
			@Override
			public CoordinateSequence edit(CoordinateSequence source, Geometry component) {
				CoordinateSequence target = PackedCoordinateSequenceFactory.DOUBLE_FACTORY.create(
						source.size(), 3, 1);
				for (int i = 0; i < source.size(); i++) {
					target.setOrdinate(i, 0, source.getX(i));
					target.setOrdinate(i, 1, source.getY(i));
					target.setOrdinate(i, 2, source.getM(i));
				}
				return target;
			}
		});
	}

	static Geometry decode(byte[] bytes) {
		try {
			Geometry decoded = new WKBReader(WKB_WRITE_GEOMETRY_FACTORY).read(bytes);
			return toJenaCoordinateSequences(decoded);
		} catch (ParseException | RuntimeException e) {
			throw new PluginException(LuceneGeoDocumentSchema.SCHEMA_MISMATCH_MESSAGE, e);
		}
	}

	private static Geometry toJenaCoordinateSequences(Geometry geometry) {
		GeometryEditor editor = new GeometryEditor(CustomGeometryFactory.theInstance());
		return editor.edit(geometry, new GeometryEditor.CoordinateSequenceOperation() {
			@Override
			public CoordinateSequence edit(CoordinateSequence source, Geometry component) {
				CoordinateSequenceDimensions dimensions = coordinateSequenceDimensions(source);
				CustomCoordinateSequence target = new CustomCoordinateSequence(source.size(), dimensions);
				for (int i = 0; i < source.size(); i++) {
					target.setOrdinate(i, 0, source.getX(i));
					target.setOrdinate(i, 1, source.getY(i));
					if (dimensions == CoordinateSequenceDimensions.XYZ
							|| dimensions == CoordinateSequenceDimensions.XYZM) {
						target.setOrdinate(i, 2, source.getZ(i));
					}
					if (dimensions == CoordinateSequenceDimensions.XYM
							|| dimensions == CoordinateSequenceDimensions.XYZM) {
						target.setOrdinate(i, 3, source.getM(i));
					}
				}
				return target;
			}
		});
	}

	private static CoordinateSequenceDimensions coordinateSequenceDimensions(CoordinateSequence sequence) {
		if (sequence.getDimension() == 2 && sequence.getMeasures() == 0) {
			return CoordinateSequenceDimensions.XY;
		}
		if (sequence.getDimension() == 3 && sequence.getMeasures() == 0) {
			return CoordinateSequenceDimensions.XYZ;
		}
		if (sequence.getDimension() == 3 && sequence.getMeasures() == 1) {
			return CoordinateSequenceDimensions.XYM;
		}
		if (sequence.getDimension() == 4 && sequence.getMeasures() == 1) {
			return CoordinateSequenceDimensions.XYZM;
		}
		throw new IllegalArgumentException("Unsupported source WKB dimensions: "
				+ sequence.getDimension() + " with " + sequence.getMeasures() + " measures");
	}
}
