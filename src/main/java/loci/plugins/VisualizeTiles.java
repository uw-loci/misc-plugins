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

package loci.plugins;

import ij.IJ;
import ij.ImagePlus;
import ij.io.OpenDialog;
import ij.plugin.PlugIn;
import ij.process.ByteProcessor;

import java.awt.Dimension;
import java.awt.Polygon;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import loci.formats.FormatException;
import loci.formats.tiff.IFD;
import loci.formats.tiff.TiffParser;

/**
 * A plugin to visualize the tile layout of a tile configuration file.
 * 
 * @author Curtis Rueden
 */
public class VisualizeTiles implements PlugIn {

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

	// -- Helper methods --

	/** Prompts the user to choose a file. */
	private File chooseFile() {
		final OpenDialog od =
			new OpenDialog("Choose a tile configuration file", "");
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

		IJ.showStatus("Reading tile dimensions");
		final String filename = coords.get(0).filename;
		final String path =
			new File(file.getParentFile(), filename).getAbsolutePath();
		final Dimension tileDim = readImageDimensions(path);

		IJ.showStatus("Calculating");
		final Pt min = getMinPoint(coords), max = getMaxPoint(coords);
		final Dimension mosaicDim = getMosaicDimensions(min, max, tileDim);
		if (mosaicDim == null) {
			IJ.showStatus("");
			return null;
		}

		IJ.showStatus("Painting image");
		final ByteProcessor ip = drawTiles(coords, min, tileDim, mosaicDim);
		final ImagePlus imp = new ImagePlus(file.getName(), ip);
		IJ.run(imp, "glasbey", "");
		IJ.showStatus("");

		return imp;
	}

	private List<Pt> readCoords(final File file) throws IOException {
		if (!file.getName().startsWith("TileConfiguration")) {
			IJ.log("WARNING: File '" + file.getPath() +
				"' may not be a tile configuration file.");
		}
		final BufferedReader in = new BufferedReader(new FileReader(file));
		final ArrayList<Pt> coords = new ArrayList<Pt>();
		while (true) {
			String line = in.readLine();
			if (line == null) break; // eof
			line = line.trim();
			if (line.equals("") || line.startsWith("#")) continue;
			if (line.startsWith("dim = ")) continue;

			final int lParen = line.lastIndexOf("(");
			final int rParen = line.lastIndexOf(")");
			final String[] tokens = line.substring(lParen + 1, rParen).split(", ");
			final Pt pt = new Pt();
			pt.filename = line.substring(0, line.indexOf(";"));
			pt.x = Double.parseDouble(tokens[0]);
			pt.y = Double.parseDouble(tokens[1]);
			coords.add(pt);
		}
		in.close();
		return coords;
	}

	private Dimension readImageDimensions(final String id) throws IOException,
		FormatException
	{
		final TiffParser tiffParser = new TiffParser(id);
		final IFD ifd = tiffParser.getFirstIFD();
		tiffParser.getStream().close();
		final int w = (int) ifd.getImageWidth();
		final int h = (int) ifd.getImageLength();
		return new Dimension(w, h);
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
			if (pt.x > max.x) max.x = pt.x;
			if (pt.y > max.y) max.y = pt.y;
		}
		return max;
	}

	private Dimension getMosaicDimensions(final Pt min, final Pt max,
		final Dimension tileDim)
	{
		final int width = (int) (max.x - min.x) + tileDim.width;
		final int height = (int) (max.y - min.y) + tileDim.height;
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

	private ByteProcessor drawTiles(final List<Pt> coords, final Pt min,
		final Dimension tileDim, final Dimension mosaicDim)
	{
		final ByteProcessor ip =
			new ByteProcessor(mosaicDim.width, mosaicDim.height);

		// draw tiles
		final int[] xPoints = new int[4], yPoints = new int[4];
		int color = 1;
		for (final Pt pt : coords) {
			final int x = (int) (pt.x - min.x), y = (int) (pt.y - min.y);
			ip.setColor(color++);
			if (color > 254) color = 1;
			xPoints[0] = x;
			yPoints[0] = y;
			xPoints[1] = x + tileDim.width;
			yPoints[1] = y;
			xPoints[2] = x + tileDim.width;
			yPoints[2] = y + tileDim.height;
			xPoints[3] = x;
			yPoints[3] = y + tileDim.height;
			final Polygon rect = new Polygon(xPoints, yPoints, xPoints.length);
			ip.fillPolygon(rect);
		}

		// draw tile outlines
		ip.setLineWidth(10);
		color = 1;
		for (final Pt pt : coords) {
			final int x = (int) (pt.x - min.x), y = (int) (pt.y - min.y);
			ip.setColor(color++);
			if (color > 254) color = 1;
			ip.drawRect(x, y, tileDim.width, tileDim.height);
		}

		return ip;
	}

	// -- Helper classes --

	static class Pt {

		String filename;
		double x, y;
	}

}
