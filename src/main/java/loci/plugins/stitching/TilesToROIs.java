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
import ij.ImageStack;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.plugin.PlugIn;
import ij.plugin.frame.RoiManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import loci.formats.FormatException;
import loci.formats.IFormatReader;
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
		final File file = TileUtils.chooseFile();
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
		new ImageJ().exitWhenQuitting(true);
		IJ.runPlugIn(TilesToROIs.class.getName(), "");
	}

	// -- Helper methods --

	private ImagePlus vizTiles(final File file) throws IOException,
		FormatException
	{
		IJ.showStatus("Initializing dataset");
		final IFormatReader in = TileUtils.initializeReader(file);
		final IMetadata meta = (IMetadata) in.getMetadataStore();

		IJ.showStatus("Reading tile coordinates");
		final List<Pt> coords = TileUtils.readCoords(meta);

		IJ.showStatus("Calculating");
		Pt[] bounds = getPointBounds(coords);
		final Pt min = bounds[0], max = bounds[1];

		final Map<Integer, Map<Integer, Map<Integer, Integer>>> coordMap =
			new HashMap<Integer, Map<Integer, Map<Integer, Integer>>>();
		final ImageStack mosaicStack = getMosaicDimension(min, max, coordMap);
		
		if (mosaicStack == null) {
			IJ.showStatus("");
			return null;
		}

		IJ.showStatus("Creating image");
		final ImagePlus imp = createImage(file.getName(), mosaicStack);
		final Calibration cal = TileUtils.getFirstCalibration(meta);
		imp.setCalibration(cal);

		Map<Integer, List<Roi>> roisBySlice =
			getRoisBySlice(coords, min, coordMap, in);
		in.close();

		// create a list of rois, sorted by slice
		List<Roi> rois = new ArrayList<Roi>();

		for (Integer slice : roisBySlice.keySet())
			rois.addAll(roisBySlice.get(slice));

		IJ.showStatus("Populating tile ROIs");
		buildTileROIs(rois);

		IJ.showStatus("");

		return imp;
	}

	/**
	 * Given a list of coordinates, a Pt constructed from global minima in
	 * those coordinates, and a mapping of z,c,t coordinates to slice indices,
	 * generates a mapping of Slice # to Rois for that slice.
	 * 
	 * Adding slices in this order will cause them to be nicely sorted in
	 * a RoiManager
	 */
	private Map<Integer, List<Roi>>
		getRoisBySlice(List<Pt> coords, Pt min,
			Map<Integer, Map<Integer, Map<Integer, Integer>>> coordMap,
			IFormatReader in)
	{
		Map<Integer, List<Roi>> roiMap = new HashMap<Integer, List<Roi>>();

		for (final Pt pt : coords) {
			final Roi roi =
				new Roi((int) (pt.x - min.x), (int) (pt.y - min.y), (int) Math
					.ceil(pt.w), (int) Math.ceil(pt.h));
			String roiFile =
				in.getSeriesUsedFiles()[in.getIndex(pt.theZ, pt.theC, pt.theT)];
			roiFile = roiFile.substring(roiFile.lastIndexOf(File.separator) + 1);
			String name = pt.name() + "; file=" + roiFile;
			roi.setName(name);

			roi.setPosition(coordMap.get(pt.theZ).get(pt.theC).get(pt.theT));

			if (roiMap.get(roi.getPosition()) == null) roiMap.put(roi.getPosition(),
				new ArrayList<Roi>());

			roiMap.get(roi.getPosition()).add(roi);
		}

		return roiMap;
	}

	/**
	 * Returns an array of two Pt objects. The first contains the minimal x, y, z,
	 * c and t values observed in the provided list of coordinates. The second
	 * contains the corresponding maximums.
	 */
	private Pt[] getPointBounds(final List<Pt> coords) {
		final Pt[] bounds = new Pt[2];
		bounds[0] = minPt();
		bounds[1] = maxPt();

		for (final Pt pt : coords) {
			// update minimum
			if (pt.x < bounds[0].x) bounds[0].x = pt.x;
			if (pt.y < bounds[0].y) bounds[0].y = pt.y;
			if (pt.theC < bounds[0].theC) bounds[0].theC = pt.theC;
			if (pt.theZ < bounds[0].theZ) bounds[0].theZ = pt.theZ;
			if (pt.theT < bounds[0].theT) bounds[0].theT = pt.theT;

			// update maximum
			final double x = pt.x + pt.w;
			final double y = pt.y + pt.h;
			if (x > bounds[1].x) bounds[1].x = x;
			if (y > bounds[1].y) bounds[1].y = y;
			if (pt.theC > bounds[1].theC) bounds[1].theC = pt.theC;
			if (pt.theZ > bounds[1].theZ) bounds[1].theZ = pt.theZ;
			if (pt.theT > bounds[1].theT) bounds[1].theT = pt.theT;
		}

		return bounds;
	}

	/**
	 * @return A Pt with maximal possible x,y,z,c,t
	 */
	private Pt maxPt() {
		final Pt max = new Pt();
		max.x = Double.NEGATIVE_INFINITY;
		max.y = Double.NEGATIVE_INFINITY;
		max.theC = Integer.MIN_VALUE;
		max.theT = Integer.MIN_VALUE;
		max.theZ = Integer.MIN_VALUE;
		return max;
	}

	/**
	 * @return A Pt with minimal possible x,y,z,c,t
	 */
	private Pt minPt() {
		final Pt min = new Pt();
		min.x = Double.POSITIVE_INFINITY;
		min.y = Double.POSITIVE_INFINITY;
		min.theC = Integer.MAX_VALUE;
		min.theT = Integer.MAX_VALUE;
		min.theZ = Integer.MAX_VALUE;
		return min;
	}

	/**
	 * Generates an ImageStack based on the minimal and maximal
	 * Pt bounds provided. Populates the given coordMap as it goes,
	 * mapping z,c,t coordinates to slice numbers.
	 * 
	 * NB: assumes T dimension is the "tiling" dimension, and groups
	 * all T planes for a given Z,C into a single slice.
	 */
	private ImageStack getMosaicDimension(final Pt min, final Pt max,
		Map<Integer, Map<Integer, Map<Integer, Integer>>> coordMap)
	{
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

		ImageStack stack = new ImageStack(width, height);

		byte[] bytes = new byte[width * height];

		int slice = 1;

		final int minZ = (int) Math.floor(min.theZ), maxZ =
			(int) Math.ceil(max.theZ), minC = (int) Math.floor(min.theC), maxC =
			(int) Math.ceil(max.theC), minT = (int) Math.floor(min.theT), maxT =
			(int) Math.ceil(max.theT);

		//NB: Assumes T is the tiling dimension
		for (int z = minZ; z <= maxZ; z++) {
			for (int c = minC; c <= maxC; c++) {
				stack.addSlice("Slice: " + slice +  ", c: " + c + ", z: " + z, bytes);
				for (int t = minT; t <= maxT; t++) {
					mapEntry(z, c, t, slice, coordMap);
				}
				slice++;
			}
		}

		return stack;
	}

	/**
	 * Maps the given z, c and t coordinates to the given index.
	 */
	private void mapEntry(final int z, final int c, final int t, final int index,
		final Map<Integer, Map<Integer, Map<Integer, Integer>>> coordMap)
	{

		Map<Integer, Map<Integer, Integer>> cMap = coordMap.get(z);

		if (cMap == null) {
			cMap = new HashMap<Integer, Map<Integer, Integer>>();
			coordMap.put(z, cMap);
		}

		Map<Integer, Integer> tMap = cMap.get(c);

		if (tMap == null) {
			tMap = new HashMap<Integer, Integer>();
			cMap.put(c, tMap);
		}

		tMap.put(t, index);
	}

	private ImagePlus createImage(final String title, final ImageStack mosaicDim)
	{
		final ImagePlus imp = new ImagePlus(title, mosaicDim);
		imp.show();
		return imp;
	}

	private void buildTileROIs(List<Roi> rois) {
		IJ.run("ROI Manager...");
		final RoiManager rm = RoiManager.getInstance();
		for (Roi r : rois) rm.addRoi(r);
		rm.runCommand("Show All");
	}
}
