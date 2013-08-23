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
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.WindowConstants;

import loci.common.DataTools;
import loci.formats.FormatException;
import loci.formats.FormatTools;
import loci.formats.IFormatReader;
import loci.formats.meta.IMetadata;
import visad.DataReferenceImpl;
import visad.Display;
import visad.DisplayImpl;
import visad.FlatField;
import visad.FunctionType;
import visad.Linear2DSet;
import visad.RealTupleType;
import visad.RealType;
import visad.SampledSet;
import visad.ScalarMap;
import visad.UnionSet;
import visad.VisADException;
import visad.java3d.DisplayImplJ3D;

/**
 * A plugin to visualize the tile layout of a multi-tile dataset in 3D.
 * 
 * @author Curtis Rueden
 */
public class VisualizeTiles implements PlugIn {

	private RealType xType, yType;
	private RealType zType, indexType;
	private RealTupleType xyType, zIndexType;
	private FunctionType tileType;

	// -- Constructor --

	public VisualizeTiles() throws VisADException {
		xType = RealType.getRealType("x");
		yType = RealType.getRealType("y");
		zType = RealType.getRealType("z");
		indexType = RealType.getRealType("index");

		xyType = new RealTupleType(xType, yType);
		zIndexType = new RealTupleType(zType, indexType);

		tileType = new FunctionType(xyType, zIndexType);
	}

	// -- PlugIn methods --

	@Override
	public void run(final String arg) {
		final File file = TileUtils.chooseFile();
		if (file == null) return;

		final GenericDialog gd = new GenericDialog("Visualize Tiles");
		gd.addCheckbox("Load_tiles", false);
		gd.showDialog();
		if (gd.wasCanceled()) return;
		final boolean loadTiles = gd.getNextBoolean();

		try {
			vizTiles(file, loadTiles);
		}
		catch (final Exception e) {
			IJ.handleException(e);
		}
	}

	// -- Main method --

	public static void main(final String... args) {
		new ImageJ().exitWhenQuitting(true);
		IJ.runPlugIn(VisualizeTiles.class.getName(), "");
	}

	// -- Helper methods --

	private void vizTiles(final File file, final boolean loadTiles)
		throws FormatException, IOException, VisADException, RemoteException
	{
		IJ.showStatus("Initializing dataset");
		final IFormatReader in = TileUtils.initializeReader(file);
		final IMetadata meta = (IMetadata) in.getMetadataStore();

		IJ.showStatus("Reading tile coordinates");
		final List<Pt> coords = TileUtils.readCoords(meta);

		IJ.showStatus("Reading data");
		final FlatField tiles = createField(coords, loadTiles ? in : null);

		in.close();

		IJ.showStatus("Creating display");
		final DisplayImpl display = createDisplay(file.getName(), tiles);
		showDisplay(display);

		IJ.showStatus("");
	}

	/**
	 * Creates a VisAD {@link FlatField} of all tiles unioned together.
	 * 
	 * @param coords List of tile coordinates.
	 * @param in Initialized {@link IFormatReader} from which to read tile
	 *          thumbnails. If null, tiles will be rendered as 2x2 random colors.
	 */
	private FlatField createField(final List<Pt> coords, final IFormatReader in)
		throws VisADException, RemoteException, FormatException, IOException
	{
		final int tileCount = coords.size();

		// map of random colors per image (only used if not loading tiles)
		final HashMap<Integer, Float> randomColors =
			in == null ? new HashMap<Integer, Float>() : null;

		// compute total number of samples
		int sampleCount = 0;
		if (in == null) {
			// 2x2 samples per tile
			sampleCount = 4 * tileCount;
		}
		else {
			// WxH samples per tile, where WxH is the thumbnail resolution
			final int seriesCount = in.getSeriesCount();
			for (int i = 0; i < seriesCount; i++) {
				in.setSeries(i);
				final int sizeX = in.getThumbSizeX();
				final int sizeY = in.getThumbSizeY();
				final int planeCount = in.getImageCount();
				sampleCount += sizeX * sizeY * planeCount;
			}
		}

		// compute minimum memory requirement
		long mem = (2 * 4 * sampleCount);
		final String unit;
		if (in == null) {
			mem /= 1024;
			unit = "KB";
		}
		else {
			mem /= 1024 * 1024;
			unit = "MB";
		}
		IJ.log("Tile field will require " + mem + " " + unit + " of memory");

		// convert tile coordinates into tile domain sets
		IJ.showStatus("Laying out tiles");

		final SampledSet[] sets = new SampledSet[tileCount];
		for (int i = 0; i < tileCount; i++) {
			final Pt pt = coords.get(i);
			final float xMin = (float) pt.x;
			final float yMin = (float) pt.y;
			final float xMax = (float) (pt.x + pt.w);
			final float yMax = (float) (pt.y + pt.h);

			int xLen = 2, yLen = 2;
			if (in != null) {
				in.setSeries(pt.i);
				xLen = in.getThumbSizeX();
				yLen = in.getThumbSizeY();
			}

			sets[i] = new Linear2DSet(xyType, xMin, xMax, xLen, yMin, yMax, yLen);
		}

		// compute range samples for each tile
		final float[][] samples = new float[2][sampleCount];
		int sampleIndex = 0;
		for (int i = 0; i < tileCount; i++) {
			IJ.showStatus("Processing tile #" + (i + 1) + "/" + tileCount);
			IJ.showProgress(i, tileCount);
			final Pt pt = coords.get(i);

			if (in == null) {
				// populate 2x2 tile with random color
				final float color = color(pt.i, randomColors);
				for (int j = 0; j < 4; j++) {
					samples[0][sampleIndex] = (float) pt.z;
					samples[1][sampleIndex] = color;
					sampleIndex++;
				}
			}
			else {
				// populate tile data at thumbnail resolution
				in.setSeries(pt.i);
				final int no = in.getIndex(pt.theZ, pt.theC, pt.theT);
				byte[] bytes = in.openThumbBytes(no);
				// convert array of bytes to array of appropriate primitives
				final int pixelType = in.getPixelType();
				final int bpp = FormatTools.getBytesPerPixel(pixelType);
				final boolean fp = FormatTools.isFloatingPoint(pixelType);
				final boolean little = in.isLittleEndian();
				final Object array = DataTools.makeDataArray(bytes, bpp, fp, little);
				// iterate over array of primitives in a general way
				for (int index=0; index<Array.getLength(array); index++) {
					float value = ((Number) Array.get(array, index)).floatValue();
					samples[0][sampleIndex] = (float) pt.z;
					samples[1][sampleIndex] = value;
					sampleIndex++;
				}
			}
		}

		// union all tiles together into a single field
		final UnionSet tilesSet = new UnionSet(xyType, sets);
		final FlatField tiles = new FlatField(tileType, tilesSet);
		tiles.setSamples(samples);

		IJ.showProgress(1);

		return tiles;
	}

	private DisplayImpl createDisplay(final String title, final FlatField tiles)
		throws VisADException, RemoteException
	{
		final DisplayImplJ3D display = new DisplayImplJ3D(title);

		// add spatial display mappings
		display.addMap(new ScalarMap(xType, Display.XAxis));
		display.addMap(new ScalarMap(yType, Display.YAxis));
		display.addMap(new ScalarMap(zType, Display.ZAxis));

		// add color display mapping
		final ScalarMap colorMap = new ScalarMap(indexType, Display.RGB);
		display.addMap(colorMap);

		// add tiles field to display
		final DataReferenceImpl ref = new DataReferenceImpl(title);
		ref.setData(tiles);
		display.addReference(ref);

		display.getGraphicsModeControl().setScaleEnable(true);
		display.getGraphicsModeControl().setTextureEnable(true);

		return display;
	}

	private float color(final int id, final HashMap<Integer, Float> colors) {
		Float color = colors.get(id);
		if (color == null) {
			// new ID; generate a random color
			color = (float) Math.random();
			colors.put(id, color);
		}
		return color;
	}

	private void showDisplay(final DisplayImpl display) {
		final JFrame frame = new JFrame(display.getName());
		frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		frame.addWindowListener(new WindowAdapter() {

			@Override
			public void windowClosing(final WindowEvent e) {
				frame.dispose();
				try {
					display.destroy();
				}
				catch (final RemoteException exc) {
					IJ.handleException(exc);
				}
				catch (final VisADException exc) {
					IJ.handleException(exc);
				}
			}
		});
		frame.getContentPane().add(display.getComponent());
		frame.pack();
		frame.setVisible(true);
	}

}
