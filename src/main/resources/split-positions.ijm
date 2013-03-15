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
