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

	public static List<Pt> readCoords(final IMetadata meta) {
		final ArrayList<Pt> coords = new ArrayList<Pt>();
		final int imageCount = meta.getImageCount();
		for (int i = 0; i < imageCount; i++) {
			// compute width and height in calibrated units
			final PositiveFloat physX = meta.getPixelsPhysicalSizeX(i);
			final PositiveFloat physY = meta.getPixelsPhysicalSizeY(i);
			final double calX = physX == null ? 1 : physX.getValue();
			final double calY = physY == null ? 1 : physY.getValue();
			final double w = meta.getPixelsSizeX(i).getValue() * calX;
			final double h = meta.getPixelsSizeY(i).getValue() * calY;

			final int planeCount = meta.getPlaneCount(i);
			for (int p = 0; p < planeCount; p++) {
				final Integer c = meta.getPlaneTheC(i, p).getValue();
				final Integer z = meta.getPlaneTheZ(i, p).getValue();
				final Integer t = meta.getPlaneTheT(i, p).getValue();
				final Double posX = meta.getPlanePositionX(i, p);
				final Double posY = meta.getPlanePositionY(i, p);
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
