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
import ij.ImageJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.io.OpenDialog;
import ij.plugin.PlugIn;
import ij.plugin.frame.RoiManager;
import ij.process.ByteProcessor;

import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import loci.formats.FormatException;
import loci.formats.ImageReader;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;

/**
 * A plugin to create ROIs matching the tile layout of a multi-tile dataset.
 * 
 * @author Curtis Rueden
 */
public class TilesToROIs implements PlugIn {

	// -- PlugIn methods --

	@Override
	public void run(final String arg) {
		final File file = chooseFile();
		if (file == null) return;

		try {
			final ImagePlus imp = vizTiles(file);
			imp.show();
		}
		catch (final Exception e) {
			IJ.handleException(e);
		}
	}

	// -- Main method --

	public static void main(final String... args) {
		new ImageJ();
		IJ.runPlugIn(TilesToROIs.class.getName(), "");
	}

	// -- Helper methods --

	/** Prompts the user to choose a file. */
	private File chooseFile() {
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

	private ImagePlus vizTiles(final File file) throws IOException,
		FormatException
	{
		IJ.showStatus("Reading tile coordinates");
		final List<Pt> coords = readCoords(file);

		IJ.showStatus("Calculating");
		final Pt min = getMinPoint(coords), max = getMaxPoint(coords);
		final Dimension mosaicDim = getMosaicDimensions(min, max);
		if (mosaicDim == null) {
			IJ.showStatus("");
			return null;
		}

		IJ.showStatus("Creating image");
		final ImagePlus imp = createImage(file.getName(), mosaicDim);

		IJ.showStatus("Populating tile ROIs");
		buildTileROIs(coords, min);

		IJ.showStatus("");

		return imp;
	}

	private List<Pt> readCoords(final File file) throws FormatException,
		IOException
	{
		final ImageReader in = new ImageReader();
		final IMetadata omeMeta = MetadataTools.createOMEXMLMetadata();
		in.setMetadataStore(omeMeta);
		in.setId(file.getAbsolutePath());

		final ArrayList<Pt> coords = new ArrayList<Pt>();
		final int imageCount = omeMeta.getImageCount();
		for (int i = 0; i < imageCount; i++) {
			in.setSeries(i);
			final int sizeX = in.getSizeX();
			final int sizeY = in.getSizeY();
			final int planeCount = omeMeta.getPlaneCount(i);
			for (int p = 0; p < planeCount; p++) {
				final Integer c = omeMeta.getPlaneTheC(i, p).getValue();
				final Integer z = omeMeta.getPlaneTheZ(i, p).getValue();
				final Integer t = omeMeta.getPlaneTheT(i, p).getValue();
				final Double posX = omeMeta.getPlanePositionX(i, p);
				final Double posY = omeMeta.getPlanePositionY(i, p);
				final Double posZ = omeMeta.getPlanePositionZ(i, p);
				coords.add(new Pt(i, p, c, z, t, sizeX, sizeY, posX, posY, posZ, t));
			}
			try {
				final Double labelX = omeMeta.getStageLabelX(i);
				final Double labelY = omeMeta.getStageLabelY(i);
				final Double labelZ = omeMeta.getStageLabelZ(i);
				coords.add(new Pt(i, sizeX, sizeY, labelX, labelY, labelZ));
			}
			catch (final NullPointerException exc) {
				// HACK: Workaround for bug in loci:ome-xml:4.4.8.
			}
		}
		in.close();

		return coords;
	}

	private Pt getMinPoint(final List<Pt> coords) {
		final Pt min = new Pt();
		min.x = Double.POSITIVE_INFINITY;
		min.y = Double.POSITIVE_INFINITY;
		for (final Pt pt : coords) {
			if (pt.x < min.x) min.x = pt.x;
			if (pt.y < min.y) min.y = pt.y;
		}
		return min;
	}

	private Pt getMaxPoint(final List<Pt> coords) {
		final Pt max = new Pt();
		max.x = Double.NEGATIVE_INFINITY;
		max.y = Double.NEGATIVE_INFINITY;
		for (final Pt pt : coords) {
			final double x = pt.x + pt.w;
			final double y = pt.y + pt.h;
			if (x > max.x) max.x = x;
			if (y > max.y) max.y = y;
		}
		return max;
	}

	private Dimension getMosaicDimensions(final Pt min, final Pt max) {
		final int width = (int) Math.ceil(max.x) - (int) Math.floor(min.x);
		final int height = (int) Math.ceil(max.y) - (int) Math.floor(min.y);
		if (width <= 0 || height <= 0) {
			IJ.error("Invalid image dimensions: " + width + " x " + height);
			return null;
		}
		if (width * height > 1500000000) {
			IJ.error("Image too large: " + width + " x " + height);
			return null;
		}
		return new Dimension(width, height);
	}

	private ImagePlus createImage(final String title, final Dimension mosaicDim) {
		final ByteProcessor ip =
			new ByteProcessor(mosaicDim.width, mosaicDim.height);
		final ImagePlus imp = new ImagePlus(title, ip);
		imp.show();
		return imp;
	}

	private void buildTileROIs(final List<Pt> coords, final Pt min) {
		IJ.run("ROI Manager...");
		final RoiManager rm = RoiManager.getInstance();
		for (final Pt pt : coords) {
			final Roi roi = new Roi(pt.x - min.x, pt.y - min.y, pt.w, pt.h);
			roi.setName(pt.name());
			rm.addRoi(roi);
		}
		rm.runCommand("Show All");
	}

}
