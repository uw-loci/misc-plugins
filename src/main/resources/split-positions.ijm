//
// split-positions.ijm
//

// Splits a dataset into N different stage positions.

// Steps to use:
// 1) Open your dataset.
// 2) Change the nPositions value (below).
// 3) Run the macro.

nPositions = 2;

getDimensions(width, height, cCount, zCount, tCount);
id = getImageID();
for (p=1; p<=nPositions; p++) {
  selectImage(id);
  run("Make Subhyperstack...",
    "channels=1-" + cCount +
    " slices=1-" + zCount +
    " frames=" + p + "-" + tCount + "-" + nPositions);
}
selectImage(id);
close();
