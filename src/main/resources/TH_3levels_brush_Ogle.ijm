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

/*
 * Threshold and calculate average intensity with Brush Tools
 *
 * Author: Jimmy Fong (fong@wisc.edu)
 *
 * Intended for IHC image analysis where the user wants to
 * calculate the average intensity per pixel.
 *
 * Workflow:
 * 1. Drag in all fluorescent images for analysis.
 * 2. Run Macro
 * 3. Prompt to drag in any Brightfield images (not analyzed)
 * 4. User sets threshold to include only desired pixels
 *    - Recommended: Choose low level above glass background,
 *      but below tissue.  Choose high level to be max.
 *    - NOTE: DON'T click any of the buttons in the threshold window
 *      Only click OK in the prompt window.
 * 5. User draws area to be excluded: pixels not included in
 *    intensity or area unless later included(if included, will be zero)
 * 6. User draws are to be included: meant for areas of tissue with
 *    intensity too low for the threshold, but shouldn't be "background"
 * 7. Algorithm calculates the average intensity of the red and
 *    included areas.
 */

//Get ids of only opened fluorescence images
names = newArray(nImages);
ids = newArray(nImages);
for (i=0; i < ids.length; i++){
	selectImage(i+1);
	ids[i] = getImageID();
	names[i] = getTitle();
}

numImages = nImages;

/* Prompt user for Brightfield images*/

waitForUser("Drag in any Brightfield Images");

//Loop through only fluo images
for (k = 0; k < numImages; k++){

	selectImage(ids[k]);
	run("Threshold...");
	origID = getImageID();
	origName = getTitle();
	run("Select None");
	getDimensions(width, height, channels, slices, frames);
	getRawStatistics(nPixels, mean,min,max);

	/*Prompt to choose threshold*/
	waitForUser("Move lower/upper limits in Threshold window\nClick OK here when threshold appropriate");
	getThreshold(lower, upper);

	/*Exclusion tool - any region selected won't be included */

	setTool("brush");
	waitForUser("Exclusion Tool: Select area to NOT include\nHold Shift for more selections\nClick ok when done");

	if(!(selectionType()== -1)){
		run("AND...", "value=0");
	}
	run("Select None");

	/*Inclusion tool - any region selected will be included, include previously excluded regions*/
	setTool("brush");
	waitForUser("Inclusion Tool:Select area to include\nHold shift for more selections\nClick ok when done");

	selectImage(origID);
	imgVals = newArray(width*height);
	numNzero = 0;

	if(!(selectionType()== -1)){	//There is a selection with inclusion tool
		title = "[Progress]";
		for (i=0; i < width; i++){
			for (j=0; j < height; j++){
				currPixel = getPixel(i,j);
				if (((currPixel < lower) || (currPixel > upper)) && selectionContains(i,j) == 0){
					//Outside range and outside inclusion selection
					setPixel(i,j,0);
					imgVals[i +j*width] = 0;
				}else{
					//Within specified range or in my inclusion selection
					imgVals[i+j *width] = currPixel;
					numNzero += 1;
				}

			}

			//Progress Bar
			print("\\Update:"+ i/(width-1)*100 + "%     |" + getBar(i, width-1)+"|");

		}
	}else{ //no selection with inclusion tool
		changeValues(0,lower,0);
		changeValues(upper+1, max,0);

		for (i=0; i < width; i++){
			for (j=0; j < height; j++){
				imgVals[ i + j*width] = getPixel(i,j);
				if (imgVals[i +j*width] !=0)
					numNzero +=1;

			}
		}

	}
	selectImage(origID);
	close();

	//Calculate average intensity
	totalIntensity = sumArray(imgVals);
	intPerPixel = totalIntensity / numNzero;

	currRow = nResults;
	setResult("Label", currRow, origName);
	setResult("Intensity/pixel", currRow, intPerPixel);
	setResult("Total Intensity", currRow, totalIntensity);
	setResult("NonZero Area (pixels)", currRow, numNzero);
	setResult("Total Area (pixels)", currRow, nPixels);
	setResult("Lower Threshold", currRow, lower);
	setResult("Upper Threshold", currRow , upper);
	updateResults();

}

//-------------------- Functions -------------------- //
function sumArray(arr){
	sum =0;
	for (i=0;i<arr.length; i++){
		sum += arr[i];
	}
	return sum;
}
function getBar(p1, p2) {
	n = 20;
	bar1 = "--------------------";
	bar2 = "********************";
	index = round(n*(p1/p2));
	if (index<1) index = 1;
	if (index>n-1) index = n-1;
	return substring(bar2, 0, index) + substring(bar1, index+1, n);
}
