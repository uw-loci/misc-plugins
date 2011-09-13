//
// StitchPrairieTiff.java
//

/*
Miscellaneous ImageJ plugins.

Copyright (c) 2010, UW-Madison LOCI
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the name of the UW-Madison LOCI nor the
      names of its contributors may be used to endorse or promote products
      derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.
*/

package loci.plugins;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import loci.formats.CoreMetadata;
import loci.formats.FormatException;
import loci.formats.IFormatReader;
import loci.formats.IFormatWriter;
import loci.formats.MetadataTools;
import loci.formats.in.MinimalTiffReader;
import loci.formats.meta.IMetadata;
import loci.formats.out.TiffWriter;
import ome.xml.model.primitives.PositiveInteger;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * A plugin to parse stage position information from a Prairie TIFF dataset's
 * XML metadata file, and convert it to a format compatible with Fiji's 2D and
 * 3D Stitching plugins.
 * 
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="http://dev.loci.wisc.edu/trac/software/browser/trunk/projects/misc-plugins/src/main/java/loci/plugins/StitchPrairieTiff.java">Trac</a>,
 * <a href="http://dev.loci.wisc.edu/svn/software/trunk/projects/misc-plugins/src/main/java/loci/plugins/StitchPrairieTiff.java">SVN</a></dd></dl>
 * 
 * @author Curtis Rueden
 */
public class StitchPrairieTiff implements PlugIn {

	private boolean stitch = true;
	private final boolean computeOverlap = true;
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

		final File tileFile = createTileConfiguration(file);
		IJ.showStatus("");
		if (tileFile == null) return;

		if (stitch) {
			IJ.showStatus("Performing stitching...");
			executeStitching(tileFile.getAbsolutePath());
			IJ.showStatus("Stitching complete.");
		}
	}

	// -- Helper methods --

	/** Prompts the user to choose a file. */
	private File chooseFile() {
		final OpenDialog od = new OpenDialog("Select the Prairie XML file", "");
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
	private File createTileConfiguration(final File dataFile) {
		final File baseDir = dataFile.getParentFile();
		try {
			IJ.showStatus("Parsing stage positions...");
			final List<Pt> coords = parseCoords(dataFile);
			Collections.sort(coords);

			IJ.showStatus("Normalizing coordinates...");
			normalizeCoords(coords);

			IJ.showStatus("Converting TIFFs to stacks...");
			final List<Pt> newCoords = convertTIFFs(baseDir, coords);

			IJ.showStatus("Generating tile configuration file...");
			final File tileFile = writeTileFile(dataFile.getParentFile(), newCoords);
			IJ.log("Wrote tile configuration:");
			IJ.log(tileFile.getAbsolutePath());
			IJ.log("");

			return tileFile;
		}
		catch (final FormatException e) {
			IJ.handleException(e);
		}
		catch (final IOException e) {
			IJ.handleException(e);
		}
		return null;
	}

	/** Ensures coordinates begin at (0, 0). */
	private void normalizeCoords(List<Pt> coords) {
		// find minimum coordinates
		double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
		for (Pt pt : coords) {
			if (pt.x < minX) minX = pt.x;
			if (pt.y < minY) minY = pt.y;
		}
		// normalize coordinates so minimum is (0, 0)
		for (Pt pt : coords) {
			pt.x -= minX;
			pt.y -= minY;
		}
	}

	/**
	 * Compiles the collection of single-plane TIFFs into TIFF stacks along Z, one
	 * stack per tile.
	 */
	private List<Pt> convertTIFFs(final File baseDir, final List<Pt> coords)
		throws FormatException, IOException
	{
		if (coords == null || coords.size() == 0) return null;
		final int firstIndex = coords.get(0).gridIndex;

		final ArrayList<Pt> newCoords = new ArrayList<Pt>();

		// compute list of stack sizes
		final ArrayList<Integer> stackSizes = computeStackSizes(coords, firstIndex);

		// convert individual TIFF files to TIFF stacks
		final IFormatReader reader = new MinimalTiffReader();
		final IFormatWriter writer = new TiffWriter();
		int outIndex = 0;
		boolean outValid = false;
		String outName = null;
		byte[] buf = null;
		int lastIndex = 0;
		int p = -1;
		for (final Pt pt : coords) {
			p++;
			IJ.showStatus("Processing plane " + p + "/" + coords.size());
			IJ.showProgress(p, coords.size());

			if (pt.gridIndex != lastIndex) {
				// new tile has begun
				lastIndex = pt.gridIndex;

				outName = "ZStack-" + pt.filename.replaceAll("_[0-9]+\\.tif", ".tif");
				final File outFile = new File(baseDir, outName);
				outValid = !outFile.exists();

				final Pt newPt = new Pt();
				newCoords.add(newPt);
				newPt.filename = outName;
				newPt.x = pt.x;
				newPt.y = pt.y;
				newPt.z = pt.z;

				writer.close();
				outIndex = 0;
			}
			if (!outValid) continue; // output stack already written

			// read planes from input and write to output
			final String inId = new File(baseDir, pt.filename).getAbsolutePath();
			final IMetadata meta = MetadataTools.createOMEXMLMetadata();
			reader.setMetadataStore(meta);
			reader.setId(inId);
			final int imageCount = reader.getImageCount();
			for (int i = 0; i < imageCount; i++) {
				if (buf == null) {
					// allocate new buffer
					buf = reader.openBytes(i);
				}
				else {
					// reuse allocated buffer
					reader.openBytes(i, buf);
				}
				if (outIndex == 0) {
					// initialize fresh writer's dimensional metadata
					final IMetadata outMeta = MetadataTools.createOMEXMLMetadata();
					final CoreMetadata core = reader.getCoreMetadata()[0];
					MetadataTools.populateMetadata(outMeta, 0, null, core);
					final int sizeZ = stackSizes.get(0);
					stackSizes.remove(0); // lazy
					outMeta.setPixelsSizeZ(new PositiveInteger(sizeZ), 0);
					writer.setMetadataRetrieve(outMeta);
					final String outId = new File(baseDir, outName).getAbsolutePath();
					writer.setId(outId);
				}
				writer.saveBytes(outIndex++, buf);
			}
			reader.close();
		}
		writer.close();

		return newCoords;
	}

	private ArrayList<Integer> computeStackSizes(final List<Pt> coords,
		final int firstIndex)
	{
		final ArrayList<Integer> stackSizes = new ArrayList<Integer>();
		int stackSize = 0;
		int lastIndex = firstIndex;
		for (final Pt pt : coords) {
			if (pt.gridIndex == lastIndex) {
				stackSize++;
			}
			else {
				stackSizes.add(stackSize);
				stackSize = 1;
				lastIndex = pt.gridIndex;
			}
		}
		stackSizes.add(stackSize);
		return stackSizes;
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
	 * @param dataFile The file from which stage coordinates should be parsed.
	 * @return The list of coordinates parsed.
	 * @throws IOException If something goes wrong reading the file.
	 */
	private static List<Pt> parseCoords(final File dataFile) throws IOException {
		final ArrayList<Pt> coords = new ArrayList<Pt>();

		try {
			final SAXParserFactory factory = SAXParserFactory.newInstance();
			final SAXParser saxParser = factory.newSAXParser();
			final DefaultHandler saxHandler = new PrairieHandler(coords);
			saxParser.parse(dataFile, saxHandler);
		}
		catch (final ParserConfigurationException e) {
			throw new IOException(e);
		}
		catch (final SAXException e) {
			throw new IOException(e);
		}

		return coords;
	}

	/**
	 * Writes the tile configuration to disk.
	 * 
	 * @param dir The directory where the data files are.
	 * @return The full path to the file where tile configurations was written.
	 * @throws IOException If something goes wrong writing the file.
	 */
	private static File writeTileFile(final File dir, final List<Pt> coords)
		throws IOException
	{
		// get current datestamp
		final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd'-'HHmmss");
		final Date now = Calendar.getInstance().getTime();
		final String datestamp = sdf.format(now);

		// create tile configuration file
		final File tileFile =
			new File(dir, "TileConfiguration-" + datestamp + ".txt");
		final PrintWriter out = new PrintWriter(tileFile);

		// write data to file
		writeHeader(out);
		for (final Pt pt : coords) {
			writeLine(out, dir, pt.filename, 3, pt.x, pt.y, pt.z);
		}
		out.close();

		return tileFile;
	}

	private static void writeHeader(final PrintWriter out) {
		out.println("# Define the number of dimensions we are working on");
		out.println("dim = 3");
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

	static class Pt implements Comparable<Pt> {

		String filename;
		double x, y, z;
		int gridIndex;

		@Override
		public int compareTo(final Pt pt) {
			if (gridIndex < pt.gridIndex) return gridIndex - pt.gridIndex;
			if (z != pt.z) return z < pt.z ? -1 : 1;
			return filename.compareTo(pt.filename);
		}

	}

	static class PrairieHandler extends DefaultHandler {

		private final List<Pt> coords;

		private String filename;
		private double x, y, z;
		private double xMicrons, yMicrons;
		private int gridIndex;

		PrairieHandler(final List<Pt> coords) {
			this.coords = coords;
			clearState();
		}

		@Override
		public void startElement(final String uri, final String localName,
			final String qName, final Attributes attributes)
		{
			if (qName.equals("File")) {
				filename = attributes.getValue("filename");
			}
			else if (qName.equals("Key")) {
				final String key = attributes.getValue("key");
				final String value = attributes.getValue("value");
				if (key.equals("positionCurrent_XAxis")) {
					x = Double.parseDouble(value);
				}
				else if (key.equals("positionCurrent_YAxis")) {
					y = Double.parseDouble(value);
				}
				else if (key.equals("positionCurrent_ZAxis")) {
					z = Double.parseDouble(value);
				}
				else if (key.equals("micronsPerPixel_XAxis")) {
					xMicrons = Double.parseDouble(value);
				}
				else if (key.equals("micronsPerPixel_YAxis")) {
					yMicrons = Double.parseDouble(value);
				}
				else if (key.equals("xYStageGridIndex")) {
					gridIndex = Integer.parseInt(value);
				}
			}
		}

		@Override
		public void endElement(final String uri, final String localName,
			final String qName)
		{
			if (qName.equals("Frame")) {
				final Pt pt = new Pt();
				pt.filename = filename;
				pt.x = x / xMicrons;
				pt.y = y / yMicrons;
				pt.z = z;
				pt.gridIndex = gridIndex;
				coords.add(pt);
				clearState();
			}
		}

		private void clearState() {
			filename = null;
			x = y = z = Double.NaN;
			xMicrons = yMicrons = 1;
			gridIndex = 0;
		}

	}

}
