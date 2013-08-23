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
import ij.io.OpenDialog;
import ij.plugin.PlugIn;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.WindowConstants;

import loci.common.DataTools;
import loci.formats.FormatException;
import loci.formats.FormatTools;
import loci.formats.IFormatReader;
import loci.formats.ImageReader;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import ome.xml.model.primitives.PositiveFloat;
import visad.ConstantMap;
import visad.DataReferenceImpl;
import visad.Display;
import visad.DisplayImpl;
import visad.FlatField;
import visad.FunctionType;
import visad.Linear2DSet;
import visad.RealTupleType;
import visad.RealType;
import visad.ScalarMap;
import visad.VisADException;
import visad.java3d.DisplayImplJ3D;

/**
 * A plugin to visualize the tile layout of a multi-tile dataset in 3D.
 * 
 * @author Curtis Rueden
 */
public class VisualizeTiles implements PlugIn {

	private RealType xType, yType;
	private RealType indexType;
	private RealTupleType xyType;
	private FunctionType tileType;

	// -- Constructor --

	public VisualizeTiles() throws VisADException {
		xType = RealType.getRealType("x");
		yType = RealType.getRealType("y");
		indexType = RealType.getRealType("index");

		xyType = new RealTupleType(xType, yType);
		tileType = new FunctionType(xyType, indexType);
	}

	// -- PlugIn methods --

	@Override
	public void run(final String arg) {
		final File file = chooseFile();
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

	private void vizTiles(final File file, final boolean loadTiles)
		throws FormatException, IOException, VisADException, RemoteException
	{
		IJ.showStatus("Initializing dataset");
		final IFormatReader in = initializeReader(file);
		final IMetadata meta = (IMetadata) in.getMetadataStore();

		IJ.showStatus("Reading tile coordinates");
		final List<Pt> coords = readCoords(meta);

		IJ.showStatus("Reading data");
		final FlatField[] tiles = createFields(coords, loadTiles ? in : null);

		in.close();

		IJ.showStatus("Creating display");
		final DisplayImpl display = createDisplay(file.getName(), coords, tiles);
		showDisplay(display);

		IJ.showStatus("");
	}

	private IFormatReader initializeReader(final File file)
		throws FormatException, IOException
	{
		final ImageReader in = new ImageReader();
		final IMetadata omeMeta = MetadataTools.createOMEXMLMetadata();
		in.setMetadataStore(omeMeta);
		in.setId(file.getAbsolutePath());
		return in;
	}

	private List<Pt> readCoords(final IMetadata meta) {
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

	/**
	 * Creates VisAD {@link FlatField} for each tile.
	 * 
	 * @param coords List of tile coordinates.
	 * @param in Initialized {@link IFormatReader} from which to read tile
	 *          thumbnails. If null, tiles will be rendered as 2x2 random colors.
	 */
	private FlatField[]
		createFields(final List<Pt> coords, final IFormatReader in)
			throws VisADException, RemoteException, FormatException, IOException
	{
		final int tileCount = coords.size();

		// map of random colors per image (only used if not loading tiles)
		final HashMap<Integer, Float> randomColors =
			in == null ? new HashMap<Integer, Float>() : null;

		final FlatField[] tiles = new FlatField[tileCount];
		for (int i = 0; i < tileCount; i++) {
			IJ.showStatus("Processing tile #" + (i + 1) + "/" + tileCount);
			IJ.showProgress(i, tileCount);
			final Pt pt = coords.get(i);

			// convert tile coordinates into tile domain set
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
			final Linear2DSet tileSet =
				new Linear2DSet(xyType, xMin, xMax, xLen, yMin, yMax, yLen);

			// compute range samples for the tile
			final float[][] samples = new float[1][xLen * yLen];
			int sampleIndex = 0;
			if (in == null) {
				// populate 2x2 tile with random color
				final float color = color(pt.i, randomColors);
				for (int j = 0; j < 4; j++) {
					samples[0][sampleIndex] = color;
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
					samples[0][sampleIndex] = value;
					sampleIndex++;
				}
			}

			tiles[i] = new FlatField(tileType, tileSet);
			tiles[i].setSamples(samples);
		}

		IJ.showProgress(1);

		return tiles;
	}

	private DisplayImpl createDisplay(final String title, final List<Pt> coords,
		final FlatField[] tiles) throws VisADException, RemoteException
	{
		final DisplayImplJ3D display = new DisplayImplJ3D(title);

		// add spatial display mappings
		display.addMap(new ScalarMap(xType, Display.XAxis));
		display.addMap(new ScalarMap(yType, Display.YAxis));

		// add color display mapping
		final ScalarMap colorMap = new ScalarMap(indexType, Display.RGB);
		display.addMap(colorMap);

		double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
		for (final Pt pt : coords) {
			if (pt.z < minZ) minZ = pt.z;
			if (pt.z > maxZ) maxZ = pt.z;
		}

		// add tile fields to display
		for (int t=0; t<tiles.length; t++) {
			IJ.showStatus("Displaying tile #" + (t + 1) + "/" + tiles.length);
			IJ.showProgress(t, tiles.length);
			final Pt pt = coords.get(t);
			final double normZ = // [-1, 1]
				minZ == maxZ ? 0 : 2 * (pt.z - minZ) / (maxZ - minZ) - 1;
			final DataReferenceImpl ref = new DataReferenceImpl(title + ":" + t);
			ref.setData(tiles[t]);
			display.addReference(ref, new ConstantMap[] {
				new ConstantMap(normZ, Display.ZAxis)
			});
		}

		display.getGraphicsModeControl().setScaleEnable(true);
		display.getGraphicsModeControl().setTextureEnable(true);

		IJ.showProgress(1);

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
