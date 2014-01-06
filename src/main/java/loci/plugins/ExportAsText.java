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

package loci.plugins;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.plugin.filter.PlugInFilter;
import ij.plugin.frame.RoiManager;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.io.FileWriter;
import java.io.IOException;
import java.util.prefs.*;

/**
 * TODO
 * 
 * @author Aivar Grislis
 */
public class ExportAsText implements PlugInFilter {
    private static final String FILE_KEY = "export_as_text_file";
    private String m_file;
    private ImagePlus imp;
    private Roi[] m_rois = {};

    @Override
    public int setup(String arg, ImagePlus imp) {
        this.imp = imp;
        return DOES_ALL;
    }

    @Override
    public void run(ImageProcessor ip) {
        boolean floatType = ip instanceof FloatProcessor;

        // get list of current ROIs
        RoiManager manager = RoiManager.getInstance();
        if (null != manager) {
            m_rois = manager.getRoisAsArray();
            //IJ.log("Got ROI Manager, count " + m_rois.length);
        }
        else IJ.log("No ROI Manager");

        //byte[] byteArray = (byte[]) ip.getPixels();

        if (showFileDialog(getFileFromPreferences())) {
            saveFileInPreferences(m_file);
        }
        else {
            return;
        }

        FileWriter fw = null;
        try {
            fw = new FileWriter(m_file);
        }
        catch (IOException e) {
            IJ.log("exception opening file " + m_file);
            IJ.handleException(e);
            return;
        }

        //StringBuffer sb = new StringBuffer();
//
        //for (byte b : byteArray)
        //{
        //    sb.append( b );
        //    sb.append("\t");
        //}

        try
        {
            //Write the header
            fw.write("x\ty\tROI\tIntensity\n");

            for(int x = 0; x < ip.getWidth(); x++)
            {
                for(int y = 0; y < ip.getHeight(); y++)
                {
                    fw.write( x  + "\t" + y + "\t" + lookUpRoi(x, y) + "\t" + getPixel(floatType, ip, x, y)  + "\n" );
                    //if (0 != lookUpRoi(x,y)) IJ.log(" x " + x + " y " + y + " roi " + lookUpRoi(x,y) + " intensity " + getPixel(floatType, ip, x, y));
                }
            }
            fw.close();
        }
        catch (IOException e)
        {
            IJ.log("exception writing file");
            IJ.handleException(e);
        }

        //IJ.log( sb.toString() );
        imp.updateAndDraw();
    }

    private String getFileFromPreferences() {
       Preferences prefs = Preferences.userNodeForPackage(this.getClass());
       return prefs.get(FILE_KEY, "");
    }

    private void saveFileInPreferences(String file) {
        Preferences prefs = Preferences.userNodeForPackage(this.getClass());
        prefs.put(FILE_KEY, file);
    }

    private boolean showFileDialog(String defaultFile) {
        //TODO shouldn't UI be in separate class?
        GenericDialog dialog = new GenericDialog("Export Image As Text");
        dialog.addStringField("Save As:", defaultFile, 24);
        dialog.showDialog();
        if (dialog.wasCanceled()) {
            return false;
        }

        m_file = dialog.getNextString();
        return true;
    }

    /**
     * Returns Roi number of a given pixel.
     *
     * @param x pixel x
     * @param y pixel y
     * @return which Roi number, 0 for none
     */
    private int lookUpRoi(int x, int y) {
        for (int i = 0; i < m_rois.length; ++i) {
            if (m_rois[i].contains(x, y)) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * Returns pixel value.
     *
     * @param floatType whether ImageProcessor is a FloatProcessor
     * @param ip ImageProcessor
     * @param x pixel x
     * @param y pixel y
     * @return pixel value
     */
    private Object getPixel(boolean floatType, ImageProcessor ip, int x, int y) {
        Object returnValue = null;
        int pixel = ip.getPixel(x,y);
        if (floatType) {
            returnValue = Float.intBitsToFloat(pixel);
        }
        else {
            returnValue = pixel;
        }
        return returnValue;
    }

}
