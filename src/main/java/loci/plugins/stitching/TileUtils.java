/*
 * #%L
 * Various plugins for ImageJ.
 * %%
 * Copyright (C) 2010 - 2014 Board of Regents of the University of
 * Wisconsin-Madison.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

package loci.plugins.stitching;

import ij.IJ;
import ij.io.OpenDialog;
import ij.measure.Calibration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import loci.formats.FormatException;
import loci.formats.IFormatReader;
import loci.formats.ImageReader;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import ome.units.UNITS;
import ome.units.quantity.Length;
import ome.xml.model.primitives.PositiveInteger;

/**
 * Utility methods for stitching plugins.
 * 
 * @author Curtis Rueden
 */
public final class TileUtils {

	private TileUtils() {
		// NB: Prevent instantiation of utility class.
	}

	/** Prompts the user to choose a file. */
	public static File chooseFile() {
		final OpenDialog od = new OpenDialog("Choose a data file", "");
		final String name = od.getFileName();
		final String dir = od.getDirectory();
		if (name == null || dir == null) {
			// no file selected
			return null;
		}
		final File file = new File(dir, name);
		if (!file.exists()) {
			IJ.error("No such file: " + file);
			return null;
		}
		return file;
	}

	public static IFormatReader initializeReader(final File file)
		throws FormatException, IOException
	{
		final ImageReader in = new ImageReader();
		final IMetadata omeMeta = MetadataTools.createOMEXMLMetadata();
		in.setMetadataStore(omeMeta);
		in.setId(file.getAbsolutePath());
		return in;
	}

	/**
	 * Creates a Calibration object using the first valid physical sizes X, Y and
	 * Z across all series in the provided Metadata. These values can be used to
	 * standardize the height and width of coordinates when combining multiple
	 * series (e.g. for coordinates generatead by {@link #readCoords(IMetadata)}).
	 */
	public static Calibration getFirstCalibration(final IMetadata meta) {
		Calibration cal = new Calibration();
		double calX = -1;
		double calY = -1;
		double calZ = -1;

		for (int i = 0; i < meta.getImageCount() &&
			(calX == -1 || calY == -1 || calZ == -1); i++)
		{
			final Double physX = physicalSizeX(meta, i);
			final Double physY = physicalSizeY(meta, i);
			final Double physZ = physicalSizeZ(meta, i);

			if (physX != null) calX = physX;
			if (physY != null) calY = physY;
			if (physZ != null) calZ = physZ;
		}

		if (calX == -1) calX = 1;
		if (calY == -1) calY = 1;
		if (calZ == -1) calZ = 1;

		cal.pixelWidth = calX;
		cal.pixelHeight = calY;
		cal.pixelDepth = calZ;

		return cal;
	}

	/**
	 * Generates a list of coordinates from all series described by the provided
	 * IMetadata object. Each coordinate consists of a stage position and physical
	 * extents. Stage positions are in uncalibrated units, while dimension lengths
	 * are standardized to the first valid calibration values found in the 
	 * provided IMetadata (as calibration can differ between series). For a
	 * compatible Calibration object, use {@link #getFirstCalibration(IMetadata)}.
	 */
	public static List<Pt> readCoords(final IMetadata meta) {
		final ArrayList<Pt> coords = new ArrayList<Pt>();
		final int imageCount = meta.getImageCount();
		Calibration cal = getFirstCalibration(meta);
		for (int i = 0; i < imageCount; i++) {
			// compute width and height normalized to the first valid calibration
			// values found
			final double calX = physicalSizeX(meta, i, 1);
			final double calY = physicalSizeY(meta, i, 1);
			final double w = sizeX(meta, i) * calX / cal.pixelWidth;
			final double h = sizeY(meta, i) * calY / cal.pixelHeight;

			final int planeCount = meta.getPlaneCount(i);
			for (int p = 0; p < planeCount; p++) {
				final Integer c = meta.getPlaneTheC(i, p).getValue();
				final Integer z = meta.getPlaneTheZ(i, p).getValue();
				final Integer t = meta.getPlaneTheT(i, p).getValue();
				final Double posX = posX(meta, i, p) / calX;
				final Double posY = posY(meta, i, p) / calY;
				final Double posZ = posZ(meta, i, p);
				coords.add(new Pt(i, p, c, z, t, w, h, posX, posY, posZ, t));
			}
			final Double labelX = stageLabelX(meta, i);
			final Double labelY = stageLabelY(meta, i);
			final Double labelZ = stageLabelZ(meta, i);
			coords.add(new Pt(i, w, h, labelX, labelY, labelZ));
		}

		return coords;
	}

	public static Integer sizeX(final IMetadata meta, final int i) {
		return value(meta.getPixelsSizeX(i));
	}

	public static Integer sizeY(final IMetadata meta, final int i) {
		return value(meta.getPixelsSizeY(i));
	}

	public static Integer sizeZ(final IMetadata meta, final int i) {
		return value(meta.getPixelsSizeZ(i));
	}

	public static Double posX(final IMetadata meta, final int i, final int p) {
		return rawValue(meta.getPlanePositionX(i, p));
	}

	public static Double posY(final IMetadata meta, final int i, final int p) {
		return rawValue(meta.getPlanePositionY(i, p));
	}

	public static Double posZ(final IMetadata meta, final int i, final int p) {
		return rawValue(meta.getPlanePositionZ(i, p));
	}

	public static Double physicalSizeX(final IMetadata meta, final int i) {
		return micronValue(meta.getPixelsPhysicalSizeX(i));
	}

	public static Double physicalSizeY(final IMetadata meta, final int i) {
		return micronValue(meta.getPixelsPhysicalSizeY(i));
	}

	public static Double physicalSizeZ(final IMetadata meta, final int i) {
		return micronValue(meta.getPixelsPhysicalSizeZ(i));
	}

	public static double physicalSizeX(final IMetadata meta, final int i,
		final double defaultValue)
	{
		return value(physicalSizeX(meta, i), defaultValue);
	}

	public static double physicalSizeY(final IMetadata meta, final int i,
		final double defaultValue)
	{
		return value(physicalSizeY(meta, i), defaultValue);
	}

	public static double physicalSizeZ(final IMetadata meta, final int i,
		final double defaultValue)
	{
		return value(physicalSizeZ(meta, i), defaultValue);
	}

	public static Double stageLabelX(final IMetadata meta, final int i) {
		return rawValue(meta.getStageLabelX(i));
	}

	public static Double stageLabelY(final IMetadata meta, final int i) {
		return rawValue(meta.getStageLabelY(i));
	}

	public static Double stageLabelZ(final IMetadata meta, final int i) {
		return rawValue(meta.getStageLabelZ(i));
	}

	public static Double rawValue(final Length l) {
		if (l == null) return null;
		final Number n = l.value();
		return n == null ? null : n.doubleValue();
	}

	public static double rawValue(final Length l, final double defaultValue) {
		return value(rawValue(l), defaultValue);
	}

	public static Double micronValue(final Length l) {
		if (l == null) return null;
		final Number n = l.value(UNITS.MICROM);
		return n == null ? null : n.doubleValue();
	}

	public static double micronValue(final Length l, final double defaultValue) {
		return value(micronValue(l), defaultValue);
	}

	public static double value(final Double d, final double defaultValue) {
		return d == null ? defaultValue : d;
	}

	public static Integer value(final PositiveInteger pi) {
		return pi == null ? null : pi.getValue();
	}

}
