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
import ij.gui.GenericDialog;
import ij.io.OpenDialog;
import ij.plugin.PlugIn;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.formats.FormatException;
import loci.formats.meta.IMetadata;
import loci.formats.services.OMEXMLService;
import loci.formats.tiff.TiffParser;

/**
 * A plugin to parse stage position information from the OME-TIFF dataset's
 * metadata block using Bio-Formats, and convert it to a format compatible with
 * Fiji's 2D and 3D Stitching plugins.
 * <p>
 * One significant difficulty is that stage coordinates are expressed in an
 * arbitrary stage unit, rather than anything convertible such as pixels or
 * microns. Hence, we must use some kind of heuristic to convert from stage
 * units to pixels, which is what the Fiji stitching plugin requires as input.
 * </p>
 * <p>
 * The current approach requires the user to provide a rough approximation of
 * tile overlap as a percentage, with a default of 20% overlap. We assume image
 * tiles were collected in a roughly rectangular grid; the heuristic will
 * probably fail with most other tile configurations.
 * </p>
 * <p>
 * The stage unit scaling algorithm (implemented in the {@link #computeScale}
 * method below) is as follows:
 * </p>
 * <ol>
 * <li>Get the list of X stage coordinates for all tiles, and sort it in
 * ascending order.</li>
 * <li>Compute the smallest difference between adjacent X coordinates, above a
 * certain minimum threshold (default 20.0). This accounts for duplicate
 * coordinates, as well as minor jitter or drift between tiles. This smallest
 * difference value represents the X spacing between adjacent tiles in the grid.
 * </li>
 * <li>Compute this same distance in pixels, which is equal to the tile width
 * times the non-overlapping tile fraction; e.g., if the overlap is set to 10%,
 * then the pixel width between adjacent tiles is 90% of tile width.</li>
 * <li>The scale factor to convert from stage units to pixels is then the ratio
 * of these two values: pixelDistance / stageUnitDistance</li>
 * <li>This computation is repeated for the Y and Z dimensions as well.</li>
 * </ol>
 * <p>
 * Once we have the stage unit scale factors, we can write out the stage
 * positions to the tile configuration file in pixels, for use by the Fiji
 * stitching plugin. There is one other minor detail, however: the stage
 * positions may be in a reverse coordinate system from the pixels. Hence, we
 * provide an "Invert stage coordinates" checkbox to indicate this. With this
 * option enabled, the stage unit scale factors will be negative, which will
 * result in the conversion to pixels in the proper direction.
 * </p>
 * 
 * @author Curtis Rueden
 */
public class StitchOmeTiff implements PlugIn {

	private double overlap = 20.0;
	private double threshold = 20.0;
	private boolean invert = true;
	private boolean stitch = true;
	private boolean computeOverlap = true;
	private FusionMethod method = FusionMethod.LINEAR_BLENDING;
	private double fusion = 1.5;
	private double regression = 0.30;
	private double maxAvg = 2.50;
	private double absolute = 3.50;

	// -- PlugIn methods --

	@Override
	public void run(final String arg) {
		final File file = chooseFile();
		if (file == null) return;

		final boolean success = chooseOptions();
		if (!success) return;

		final String tileConfigPath = createTileConfiguration(file);
		IJ.showStatus("");
		if (tileConfigPath == null) return;

		if (stitch) {
			IJ.showStatus("Performing stitching...");
			executeStitching(tileConfigPath);
			IJ.showStatus("Stitching complete.");
		}
	}

	// -- Helper methods --

	/** Prompts the user to choose a file. */
	private File chooseFile() {
		final OpenDialog od = new OpenDialog("Choose a file from your dataset", "");
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

	/** Prompts the user for plugin parameter values. */
	private boolean chooseOptions() {
		final GenericDialog gd = new GenericDialog("Options");
		gd.addMessage("Tile layout parameters:");
		gd.addNumericField("Overlap (%)", overlap, 0);
		gd.addNumericField("Stage_unit_threshold", threshold, 2);
		gd.addCheckbox("Invert_X_coordinates (hack)", invert);
		gd.addCheckbox("Perform_stitching now", stitch);
		// NB: For now, commented out ability to disable overlap computation,
		// because ImageJ is not passing the flag to the stitching plugin properly.
		// So regardless of whether the box is checked, the overlap gets computed.
//		gd.addCheckbox("compute_overlap (otherwise use the coordinates as-is)",
//			computeOverlap);
		gd.addMessage("Stitching parameters:");
		gd.addChoice("Fusion_Method", FusionMethod.labels(),
			FusionMethod.LINEAR_BLENDING.toString());
		gd.addNumericField("Fusion alpha", fusion, 2);
		gd.addNumericField("Regression Threshold", regression, 2);
		gd.addNumericField("Max/Avg Displacement Threshold", maxAvg, 2);
		gd.addNumericField("Absolute Avg Displacement Threshold", absolute, 2);
		gd.showDialog();
		if (gd.wasCanceled()) return false;
		overlap = gd.getNextNumber();
		threshold = gd.getNextNumber();
		invert = gd.getNextBoolean();
		stitch = gd.getNextBoolean();
//		computeOverlap = gd.getNextBoolean();
		method = FusionMethod.get(gd.getNextChoice());
		fusion = gd.getNextNumber();
		regression = gd.getNextNumber();
		maxAvg = gd.getNextNumber();
		absolute = gd.getNextNumber();
		return true;
	}

	/**
	 * Generates the tile configuration file for use with the Fiji Stitching
	 * plugins.
	 */
	private String createTileConfiguration(final File dataFile) {
		try {
			IJ.showStatus("Parsing stage positions...");
			final IMetadata meta = parseMetadata(dataFile);

			IJ.showStatus("Generating tile configuration file...");
			final double[] scaleFactors =
				computeScale(meta, overlap, threshold, invert);
			final File tileFile =
				writeTileFile(dataFile.getParentFile(), meta, scaleFactors);
			IJ.log("Wrote tile configuration:");
			IJ.log(tileFile.getAbsolutePath());
			IJ.log("");

			return tileFile.getAbsolutePath();
		}
		catch (final FormatException e) {
			IJ.handleException(e);
		}
		catch (final IOException e) {
			IJ.handleException(e);
		}
		return null;
	}

	/** Executes the stitching plugin using the given tile configuration. */
	private void executeStitching(final String tileConfigPath) {
		final StringBuilder sb = new StringBuilder();
		sb.append("layout=[" + tileConfigPath + "]");
		if (computeOverlap) sb.append(" compute_overlap");
		// NB: Hard-coded the channels for now.
		sb.append(" channels_for_registration=[Red, Green and Blue]");
		sb.append(" rgb_order=rgb");
		sb.append(" fusion_method=[" + method + "]");
		sb.append(" fusion=" + fusion);
		sb.append(" regression=" + regression);
		sb.append(" max/avg=" + maxAvg);
		sb.append(" absolute=" + absolute);
		IJ.run("Stitch Collection of Images", "layout=[" + tileConfigPath + "] " +
			"compute_overlap channels_for_registration=[Red, Green and Blue] " +
			"rgb_order=rgb fusion_method=[" + method + "] fusion=" + fusion +
			" regression=" + regression + " max/avg=" + maxAvg + " absolute=" +
			absolute);
	}

	// -- Utility methods --

	/**
	 * Parses the stage position metadata.
	 * 
	 * @param dataFile The file from which metadata should be parsed.
	 * @return The metadata object containing parsed values.
	 * @throws FormatException If something goes wrong while parsing values.
	 * @throws IOException If something goes wrong reading the file.
	 */
	private static IMetadata parseMetadata(final File dataFile)
		throws FormatException, IOException
	{
		final TiffParser tiffParser = new TiffParser(dataFile.getAbsolutePath());
		final String xml = tiffParser.getComment();
		tiffParser.getStream().close();
		try {
			final OMEXMLService service =
				new ServiceFactory().getInstance(OMEXMLService.class);
			if (service == null) return null;
			return service.createOMEXMLMetadata(xml);
		}
		catch (final DependencyException exc) {
			return null;
		}
		catch (final ServiceException exc) {
			return null;
		}
	}

	/**
	 * Computes the scale factor between stage position coordinates and pixel
	 * coordinates, assuming the given percentage overlap between tiles.
	 * 
	 * @param meta The metadata object containing raw stage position values.
	 * @param overlap The overlap between tiles, in percentage of the tile.
	 * @param threshold The minimum distance between stage coordinate values
	 *          (helps to account for stage jitter and drift).
	 * @param invert Whether to invert the X stage coordinates.
	 * @return The scale factor to use for each dimension (X, Y and Z).
	 */
	private static double[] computeScale(final IMetadata meta,
		final double overlap, final double threshold, final boolean invert)
	{
		final int imageCount = meta.getImageCount();

		// compile sorted list of stage positions
		final ArrayList<Double> xList = new ArrayList<Double>();
		final ArrayList<Double> yList = new ArrayList<Double>();
		final ArrayList<Double> zList = new ArrayList<Double>();
		for (int iIndex = 0; iIndex < imageCount; iIndex++) {
			final Double stageLabelX = TileUtils.stageLabelX(meta, iIndex);
			final Double stageLabelY = TileUtils.stageLabelY(meta, iIndex);
			final Double stageLabelZ = TileUtils.stageLabelZ(meta, iIndex);
			if (stageLabelX != null) xList.add(stageLabelX);
			if (stageLabelY != null) yList.add(stageLabelY);
			if (stageLabelZ != null) zList.add(stageLabelZ);
		}
		Collections.sort(xList);
		Collections.sort(yList);
		Collections.sort(zList);

		// compute minimal gap across each dimension (in stage units)
		final double xSpacing = computeSpacing(xList, threshold);
		final double ySpacing = computeSpacing(yList, threshold);
		final double zSpacing = computeSpacing(zList, threshold);
		final boolean hasZ = hasZ(meta);
		IJ.log("X spacing (in stage units) = " + xSpacing);
		IJ.log("Y spacing (in stage units) = " + ySpacing);
		if (hasZ) IJ.log("Z spacing (in stage units) = " + zSpacing);

		// compute pixels per stage unit
		final int sizeX = meta.getPixelsSizeX(0).getValue();
		final int sizeY = meta.getPixelsSizeY(0).getValue();
		final int sizeZ = meta.getPixelsSizeZ(0).getValue();
		final double nonOverlapFraction = (100 - overlap) / 100.0;
		final double nonOverlapX = nonOverlapFraction * sizeX;
		final double nonOverlapY = nonOverlapFraction * sizeY;
		final double nonOverlapZ = nonOverlapFraction * sizeZ;
		IJ.log("X non-overlapping pixels = " + nonOverlapX);
		IJ.log("Y non-overlapping pixels = " + nonOverlapY);
		if (hasZ) IJ.log("Z non-overlapping pixels = " + nonOverlapZ);
		final double xScale = nonOverlapX / xSpacing;
		final double yScale = nonOverlapY / ySpacing;
		final double zScale = nonOverlapZ / zSpacing;
		IJ.log("X scale factor = " + xScale);
		IJ.log("Y scale factor = " + yScale);
		if (hasZ) IJ.log("Z scale factor = " + zScale);

		if (invert) return new double[] { -xScale, yScale, zScale };
		return new double[] { xScale, yScale, zScale };
	}

	/** Computes the minimum spacing between values of the given sorted list. */
	private static double computeSpacing(final ArrayList<Double> values,
		final double threshold)
	{
		double minSpacing = Double.POSITIVE_INFINITY;
		for (int i = 1; i < values.size(); i++) {
			final double spacing = values.get(i) - values.get(i - 1);
			if (spacing > threshold && spacing < minSpacing) minSpacing = spacing;
		}
		return minSpacing;
	}

	/**
	 * Writes the tile configuration to disk.
	 * 
	 * @param dir The directory where the data files are.
	 * @param meta Metadata from which to extract the tile configuration.
	 * @param scaleFactors The scale factors to use when converting from stage
	 *          coordinates to pixels.
	 * @return The full path to the file where tile configuration was written.
	 * @throws IOException If something goes wrong writing the file.
	 */
	private static File writeTileFile(final File dir, final IMetadata meta,
		final double[] scaleFactors) throws IOException
	{
		// create tile configuration file
		final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd'-'HHmmss");
		final Date now = Calendar.getInstance().getTime();
		final File tileFile =
			new File(dir, "TileConfiguration-" + sdf.format(now) + ".txt");
		final PrintWriter out = new PrintWriter(tileFile);

		// write tile configuration
		final int imageCount = meta.getImageCount();
		final int dim = hasZ(meta) ? 3 : 2;
		for (int iIndex = 0; iIndex < imageCount; iIndex++) {
			// get stage positions for this Image
			final Double stageLabelX = TileUtils.stageLabelX(meta, iIndex);
			final Double stageLabelY = TileUtils.stageLabelY(meta, iIndex);
			final Double stageLabelZ = TileUtils.stageLabelZ(meta, iIndex);
			if (stageLabelX == null || stageLabelY == null) {
				IJ.log("Warning: Image #" + iIndex + " has no stage position");
				continue;
			}
			final double stageX = scaleFactors[0] * stageLabelX;
			final double stageY = scaleFactors[1] * stageLabelY;
			final double stageZ =
				stageLabelZ == null ? 0 : (scaleFactors[2] * stageLabelZ);

			// parse filenames
			final int tiffDataCount = meta.getTiffDataCount(iIndex);
			for (int tdIndex = 0; tdIndex < tiffDataCount; tdIndex++) {
				final String fileName = meta.getUUIDFileName(iIndex, tdIndex);
				if (fileName == null || fileName.isEmpty()) {
					IJ.log("Warning: No file name for Image #" + iIndex + ", TiffData #" +
						tdIndex);
					continue;
				}
				if (iIndex == 0 && tdIndex == 0) writeHeader(out, dim);
				writeLine(out, dir, fileName, dim, stageX, stageY, stageZ);
			}
		}
		out.close();
		return tileFile;
	}

	private static void writeHeader(final PrintWriter out, final int dim) {
		out.println("# Define the number of dimensions we are working on");
		out.println("dim = " + dim);
		out.println();
		out.println("# Define the image coordinates");
	}

	private static void writeLine(final PrintWriter out, final File dir,
		final String fileName, final int dim, final double... stage)
	{
		final File file = new File(dir, fileName);
		if (!file.exists()) {
			IJ.log("Warning: file '" + file.getAbsolutePath() + "' does not exist.");
			return;
		}
		final StringBuilder sb = new StringBuilder();
		sb.append(file.getAbsolutePath() + "; ; (");
		for (int i = 0; i < dim; i++) {
			if (i > 0) sb.append(", ");
			sb.append(stage[i]);
		}
		sb.append(")");
		out.println(sb.toString());
	}

	private static boolean hasZ(final IMetadata meta) {
		return meta.getPixelsSizeZ(0).getValue() > 1;
	}

	// -- Helper classes --

	public static enum FusionMethod {
		AVERAGE("Average"), LINEAR_BLENDING("Linear Blending"), MAX_INTENSITY(
			"Max. Intensity"), MIN_INTENSITY("Min. Intensity"), NONE("None");

		private static final Map<String, FusionMethod> METHODS;

		static {
			METHODS = new ConcurrentHashMap<String, FusionMethod>();
			for (final FusionMethod method : values()) {
				METHODS.put(method.label, method);
			}
		}

		private final String label;

		private FusionMethod(final String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}

		public static String[] labels() {
			final String[] labels = new String[values().length];
			int i = 0;
			for (final FusionMethod fm : values()) {
				labels[i++] = fm.toString();
			}
			return labels;
		}

		public static FusionMethod get(final String label) {
			return METHODS.get(label);
		}
	}

}
