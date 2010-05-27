/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.gui.WaitForUserDialog;
import ij.plugin.filter.PlugInFilter;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import ij.process.ByteProcessor;
import ij.process.ShortProcessor;

import java.awt.Rectangle;
import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;

/**
 *
 * @author Aivar Grislis
 */
public class Roi_Map implements PlugInFilter {
    static final int MAX_8_BIT = 255;
    static final int MAX_16_BIT = 65535;
    static final String OUT_TITLE = "Roi Map";
    ImagePlus m_imp;
    
    public int setup(String arg, ImagePlus imp) {
        if (arg.equals("about")) {
            showAbout();
            return DONE;
        }
        m_imp = imp;
        return DOES_ALL+NO_CHANGES;
    }

    private void showAbout() {
       IJ.showMessage("Creates image showing ROI regions.", "Roi Map");
    }
    
    public void run(ImageProcessor ip) {
        // get list of current ROIs
        Roi[] rois = {};
        RoiManager manager = RoiManager.getInstance();
        if (null != manager) {
            rois = manager.getRoisAsArray();
        }
        
        if (0 < rois.length) {
            ImageProcessor outImageProcessor = null;
            int width = ip.getWidth();
            int height = ip.getHeight();
        
            if (MAX_8_BIT >= rois.length) {
                outImageProcessor = new ByteProcessor(width, height);
                outImageProcessor.setColorModel(createColorModel());
            }
            else if (MAX_16_BIT > rois.length) {
                outImageProcessor = new ShortProcessor(width, height);
            }
            
            if (null != outImageProcessor) {
                ImagePlus outImagePlus = new ImagePlus(OUT_TITLE, outImageProcessor);
 
                int roiColor = 1;
                for (Roi roi: rois) {
                    outImageProcessor.setColor(roiColor++);
                    Rectangle bounds = roi.getBounds();
                
                    for (int y = 0; y < bounds.height; ++y) {
                        for (int x = 0; x < bounds.width; ++x) {
                            if (roi.contains(bounds.x + x, bounds.y + y)) {
                                outImageProcessor.drawPixel(bounds.x + x, bounds.y + y);
                            }
                        }
                    }
                }
                
                outImagePlus.show();
            }
            else {
                // too many ROIs
                new WaitForUserDialog("Too Many ROIs", "There are too many ROIs selected.").show();
            }
        }
        else {
            // no ROIs
            new WaitForUserDialog("No ROIs", "There are no ROIs selected.").show();
        }
    }

    private ColorModel createColorModel() {
        //TODO hardcode a palette generated in this fashion
        final byte[] rValue = { (byte)  96, (byte) 192, (byte)  32, (byte) 128, (byte) 224, (byte)  64, (byte) 160, (byte) 255 };
        final byte[] gValue = { (byte) 224, (byte)  64, (byte) 160, (byte) 255, (byte)  96, (byte) 192, (byte)  32, (byte) 128 };
        final byte[] bValue = { (byte) 128, (byte) 255, (byte)  64, (byte) 192 };

        byte[] rPalette = new byte[256];
        byte[] gPalette = new byte[256];
        byte[] bPalette = new byte[256];
        byte[] aPalette = new byte[256];

        // set transparency //TODO not working
        aPalette[0] = (byte) 0x00;
        for (int i = 0; i < 256; ++i) {
            aPalette[i] = (byte) 0xff;
        }

        int i = 1;
        for (int red = 0; red < 8; ++red) {
            for (int green = 0; green < 8; ++green) {
                for (int blue = 0; blue < 4; ++blue) {
                    if (i < 256) {
                        rPalette[i] = rValue[red];
                        gPalette[i] = gValue[green];
                        bPalette[i] = bValue[blue];
                        ++i;
                    }
                }
            }
        }
        ColorModel colorModel = new IndexColorModel(8, 256, rPalette, gPalette, bPalette, aPalette);
        return colorModel;
    }
}
