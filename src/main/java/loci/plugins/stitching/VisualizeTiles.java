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
import ij.io.OpenDialog;
import ij.plugin.PlugIn;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JFrame;
import javax.swing.WindowConstants;

import loci.formats.FormatException;
import loci.formats.ImageReader;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import ome.xml.model.primitives.PositiveFloat;
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
		final File file = chooseFile();
		if (file == null) return;

		try {
			vizTiles(file);
		}
		catch (final Exception e) {
			IJ.handleException(e);
		}
	}

	// -- Main method --

	public static void main(final String... args) {
		new ImageJ();
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

	private void vizTiles(final File file) throws FormatException,
		IOException, VisADException, RemoteException
	{
		IJ.showStatus("Reading tile coordinates");
		final List<Pt> coords = readCoords(file);

		IJ.showStatus("Creating display");
		final DisplayImpl display = createDisplay(file.getName(), coords);
		showDisplay(display);

		IJ.showStatus("");
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

			// compute width and height in calibrated units
			final PositiveFloat physX = omeMeta.getPixelsPhysicalSizeX(i);
			final PositiveFloat physY = omeMeta.getPixelsPhysicalSizeY(i);
			final double calX = physX == null ? 1 : physX.getValue();
			final double calY = physY == null ? 1 : physY.getValue();
			final double w = in.getSizeX() * calX;
			final double h = in.getSizeY() * calY;
			IJ.log("For image # " + i + ": tile dimension is " + w + " x " + h);//TEMP

			final int planeCount = omeMeta.getPlaneCount(i);
			for (int p = 0; p < planeCount; p++) {
				final Integer c = omeMeta.getPlaneTheC(i, p).getValue();
				final Integer z = omeMeta.getPlaneTheZ(i, p).getValue();
				final Integer t = omeMeta.getPlaneTheT(i, p).getValue();
				final Double posX = omeMeta.getPlanePositionX(i, p);
				final Double posY = omeMeta.getPlanePositionY(i, p);
				final Double posZ = omeMeta.getPlanePositionZ(i, p);
				coords.add(new Pt(i, p, c, z, t, w, h, posX, posY,
					posZ, t));
			}
			try {
				final Double labelX = omeMeta.getStageLabelX(i);
				final Double labelY = omeMeta.getStageLabelY(i);
				final Double labelZ = omeMeta.getStageLabelZ(i);
				coords.add(new Pt(i, w, h, labelX, labelY, labelZ));
			}
			catch (final NullPointerException exc) {
				// HACK: Workaround for bug in loci:ome-xml:4.4.8.
			}
		}
		in.close();

		return coords;
	}

	private DisplayImpl createDisplay(final String title, final List<Pt> coords)
		throws VisADException, RemoteException
	{
		final DisplayImplJ3D display = new DisplayImplJ3D(title);
		final int tileCount = coords.size();

		// map of random colors per image
		final HashMap<Integer, Float> randomColors = new HashMap<Integer, Float>();

		// convert tile coordinates into tile domain sets and range samples
		final SampledSet[] sets = new SampledSet[tileCount];
		final float[][] samples = new float[2][4 * tileCount];
		for (int i=0; i<tileCount; i++) {
			IJ.showProgress(i, tileCount);
			final Pt pt = coords.get(i);
			final float xMin = (float) pt.x;
			final float yMin = (float) pt.y;
			final float xMax = (float) (pt.x + pt.w);
			final float yMax = (float) (pt.y + pt.h);
			sets[i] = new Linear2DSet(xyType, xMin, xMax, 2, yMin, yMax, 2);
			final float color = color(pt.i, randomColors);
			for (int j=0; j<4; j++) {
				final int index = 4 * i + j;
				samples[0][index] = (float) pt.z;
				samples[1][index] = color;
			}
		}

		// union all tiles together into a single field
		final UnionSet tilesSet = new UnionSet(xyType, sets);
		final FlatField tiles = new FlatField(tileType, tilesSet);
		tiles.setSamples(samples);

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
