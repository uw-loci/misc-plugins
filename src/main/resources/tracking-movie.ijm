//
// tracking-movie.ijm
//

// This script creates an animation from ROIs added to the ROI manager.
// It uses the associated image plane of each ROI, adjusted spatially
// such that the first (X, Y) coordinate of each ROI occupies the same
// location, as a "poor man's" registration technique. Each plane is
// then converted to RGB Color. Lastly, all individual color planes are
// assembled into the final movie.

// To use, open your hyperstack and proceed to mark points using the
// point tool. Add each point to the ROI Manager by pressing T. Once you
// have marked all desired points, run the script to generate the movie.

// Authors: Curtis Rueden & Joe Szulczewski

setBatchMode(true);

// change this to your liking; it will be used to label each movie frame
labelPrefix = "Frame";

// ask for the big dataset's 5D dimensions
getDimensions(width, height, channels, slices, frames);
id = getImageID();

// loop over ROIs
//for (f=0; f<frames; f++) {
xMax = 0;
yMax = 0;
for (f=0; f<roiManager("count"); f++) {
  // select the ROI, to query its (X, Y) coordinates
  roiManager("Select", f);
  getSelectionCoordinates(x, y);
  if (x[0] > xMax) xMax = x[0];
  if (y[0] > yMax) yMax = y[0];
}

// loop over ROIs
for (f=0; f<roiManager("count"); f++) {
  selectImage(id);
  // select the ROI, because it changes the ZCT position to match it
  roiManager("Select", f);
  // make sure we know the (X, Y) coordinates of this particular ROI
  getSelectionCoordinates(x, y);
  // duplicate the current image plane (which the ROI marked)
  run("Duplicate...", "title=[" + labelPrefix + " " + f + "]");
  // convert image plane to RGB color for our movie
  duplicateId = getImageID();
  run("RGB Color");
  rgbId = getImageID();
  selectImage(duplicateId);
  close();
  // pad the image to adjust for skew in X and/or Y
  selectImage(rgbId);
  w = width + xMax - x[0];
  h = height + yMax - y[0];
  run("Canvas Size...", "width=" + w + " height=" + h + " position=Bottom-Right zero");
}
// combine all individual planes into a single movie
run("Images to Stack", "name=Movie title=[" + labelPrefix + "] use");

setBatchMode(false);
