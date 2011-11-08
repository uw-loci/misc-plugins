/*
*	Intensity Macro for ImageJ - customized for Ogle Group 
*	Author: Jimmy Fong (fong@wisc.edu)
*	Modified by: Jayne Squirrell (jsquirre@wisc.edu)
*	Purpose:  Performs a background subtraction of intensity 
*		in a ROI selected from a Brightfield image.
*		Calculates the background-subtracted total 
*		intensity, and intensity per brightfield area.
*		Also uses a user selected ROI from the Intensity image.
*		Calculates the background-subtracted total intensity, 
*		and intensity per fluorescent area.
*		Also saves the fluorescent intensity images after background subtraction over 
*		both the BF area and the FL area.
*/		


//Prompt for Brightfield Image
Dialog.create("Please Select Brightfield Image");
Dialog.addMessage("Please Select Brightfield Image");
Dialog.show();

bfImage = File.openDialog("Brightfield Image");
open(bfImage);
bfImageName = getTitle();

Dialog.create("Draw outline of cell/EB");
Dialog.addMessage("Draw outline of cell/EB");
Dialog.show();

setTool("freehand");

waitForUser("Use this selection");
getSelectionCoordinates(xcoords, ycoords);
getStatistics(bfarea);
//print(bfarea);

//Open Intensity Image
run("Open Next");
intImageName = getTitle();

//Prompt for outline of fluorescent intensity region
Dialog.create("Draw outline of fluorescent region");
Dialog.addMessage("Draw outline of fluorescent region");
Dialog.show();

setTool("freehand");

waitForUser("Use this selection");
getSelectionCoordinates(xcoordsfl, ycoordsfl);
getStatistics(flarea);
//print(flarea);

//deselect fluorescent image outline

run ("Select None");

//Duplicate Fluorescent Image for pre-BG subtract
run("Duplicate...");
dupIntImage = getTitle();



//Prompt for Background Selection
Dialog.create("Select Background Area");
Dialog.addNumber("% Background to retain", 10);
Dialog.show();
perKept = Dialog.getNumber();

//Make 50 by 50 circle the default bg selection
setTool("oval");
makeOval(25,25,50,50);
getStatistics(bgarea);
waitForUser("Use this as background");


//Calculation of threshold for "background subtraction"
getHistogram(values, counts, 256);
//print(values[1]);


totalCounts = sumArray(counts);
countThresh = round(perKept/100 * totalCounts);
//print("total counts is: " + totalCounts);
//print("count Thresh is: " + countThresh);

if(countThresh == 0)
	countThresh =1;

tailCounts = 0;
firstThrough = 1;
for (i = counts.length - 1; i != 0; i --){
	tailCounts += counts[i];  
	//print("counts at " + i + "is " + counts[i]); 
	//print("tailCounts is" + tailCounts);
	if((tailCounts >= countThresh)&& (firstThrough == 1) ){
		valueThreshold = i;
		firstThrough = 0;
	}
}
//print("value thresh is" + valueThreshold);

selectWindow(intImageName);
makeSelection("freehand",xcoords, ycoords);
run("Subtract...", "value=" + valueThreshold + " slice");


getHistogram(NormValues, NormCounts, 256);

//test normvalues



//print("total norm intensity");

//Calculate the total normalized intensity
totalNormIntensity =0;
for(i=0; i<NormValues.length; i++){
	totalNormIntensity += (NormValues[i] * NormCounts[i]);
//	print("NormCounts at " + i + "is: " + NormCounts[i]);
//	print("NormValues at " + i + "is: " + NormValues[i]);
//	print("totalNormIntensity is: "+ totalNormIntensity);
//	print("***************************************");
}
NormIntperBFArea = totalNormIntensity/bfarea;

selectWindow(dupIntImage);
makeSelection("freehand",xcoordsfl, ycoordsfl);
run("Subtract...", "value=" + valueThreshold + " slice");


getHistogram(flNormValues, flNormCounts, 256);

//test flnormvalues



//print("fltotal norm intensity");

//Calculate the fl total normalized intensity
fltotalNormIntensity =0;
for(i=0; i<flNormValues.length; i++){
	fltotalNormIntensity += (flNormValues[i] * flNormCounts[i]);
//	print("flNormCounts at " + i + "is: " + flNormCounts[i]);
//	print("flNormValues at " + i + "is: " + flNormValues[i]);
//	print("fltotalNormIntensity is: "+ fltotalNormIntensity);
//	print("***************************************");
}

NormIntperFLArea = fltotalNormIntensity/flarea;

//print("\n"+intImageName +"\nTotal Normalized Intensity is: " + totalNormIntensity + "\nTotal Area(BF) is: " + bfarea + "\n Normalized Intensity per BrightField Area is:" + NormIntperBFArea);
//print(intImageName);

//print("\n"+intImageName +"\nflTotal Normalized Intensity is: " + fltotalNormIntensity + "\nTotal Area(FL) is: " + flarea + "\n Normalized Intensity per fluorescent Area is:" + NormIntperFLArea);
//print(intImageName);

selectWindow(intImageName);
getDimensions(width, height, channels, totalslices, frames);
actualanalyzedslice = getSliceNumber();
slicefrombottom = (totalslices - actualanalyzedslice) + 1;

currRow = nResults;
setResult("Label", currRow, intImageName);
setResult("Slice from bottom", currRow, slicefrombottom);
setResult("Background Area", currRow, bgarea);
setResult("Threshold", currRow, valueThreshold);
setResult("BF Total Bckgnd Subtracted Int", currRow , totalNormIntensity);
setResult("Fluor Total Bckgnd Subtracted Int", currRow , fltotalNormIntensity);
setResult("Brightfield Area(pixels)", currRow, bfarea);
setResult("Fluorescent Area(pixels)", currRow, flarea);
setResult("Normalized Int. (Total Bckgnd sub int./BF area", currRow, NormIntperBFArea);
setResult("Normalized Int. (Total Bckgnd sub int./FL area", currRow, NormIntperFLArea);
setResult("% Background Retained", currRow, perKept);
updateResults();

//to save image files with background subtracted
currentdirectory = File.directory;
selectWindow(intImageName);
saveAs("tiff", currentdirectory + "/bgndsub_bfarea_" + intImageName);
selectWindow(dupIntImage);
saveAs("tiff", currentdirectory + "/bgndsub_flarea_" + dupIntImage);


function sumArray(arr){
	sum =0;
	for (i=0;i<arr.length -1; i++){
		sum += arr[i];
	}
	return sum;
}







