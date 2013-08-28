/*
 * #%L
 * Various plugins for ImageJ.
 * %%
 * Copyright (C) 2010 - 2013 Board of Regents of the University of
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
import ome.xml.model.primitives.PositiveFloat;

/**
 * A plugin to visualize the tile layout of a multi-tile dataset in 3D.
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
			final PositiveFloat physX = meta.getPixelsPhysicalSizeX(i);
			final PositiveFloat physY = meta.getPixelsPhysicalSizeY(i);
			final PositiveFloat physZ = meta.getPixelsPhysicalSizeZ(i);

			if (physX != null) calX = physX.getValue();
			if (physY != null) calY = physY.getValue();
			if (physZ != null) calZ = physZ.getValue();
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
			final PositiveFloat physX = meta.getPixelsPhysicalSizeX(i);
			final PositiveFloat physY = meta.getPixelsPhysicalSizeY(i);
			final double calX = physX == null ? 1 : physX.getValue();
			final double calY = physY == null ? 1 : physY.getValue();
			final double w =
				meta.getPixelsSizeX(i).getValue() * calX / cal.pixelWidth;
			final double h =
				meta.getPixelsSizeY(i).getValue() * calY / cal.pixelHeight;

			final int planeCount = meta.getPlaneCount(i);
			for (int p = 0; p < planeCount; p++) {
				final Integer c = meta.getPlaneTheC(i, p).getValue();
				final Integer z = meta.getPlaneTheZ(i, p).getValue();
				final Integer t = meta.getPlaneTheT(i, p).getValue();
				final Double posX = meta.getPlanePositionX(i, p) / calX;
				final Double posY = meta.getPlanePositionY(i, p) / calY;
				final Double posZ = meta.getPlanePositionZ(i, p);
				coords.add(new Pt(i, p, c, z, t, w, h, posX, posY, posZ, t));
			}
			try {
				final Double labelX = meta.getStageLabelX(i);
				final Double labelY = meta.getStageLabelY(i);
				final Double labelZ = meta.getStageLabelZ(i);
				coords.add(new Pt(i, w, h, labelX, labelY, labelZ));
			}
			catch (final NullPointerException exc) {
				// HACK: Workaround for bug in loci:ome-xml:4.4.8.
			}
		}

		return coords;
	}

}
