package de.csbdresden.stardist;

import java.net.URL;
import java.util.List;

import org.scijava.app.StatusService;
import org.scijava.command.CommandService;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.ui.DialogPrompt.MessageType;
import org.scijava.ui.UIService;

import ij.ImagePlus;
import ij.gui.PointRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;

public abstract class StarDist2DBase {

    @Parameter
    protected LogService log;

    @Parameter
    protected UIService ui;

    @Parameter
    protected CommandService command;

    @Parameter
    protected DatasetService dataset;

    @Parameter
    protected StatusService status;

    // ---------

    protected boolean exportPointRois = false;
    protected boolean exportBboxRois = false;

    protected RoiManager roiManager = null;
    protected ImagePlus labelImage = null;
    protected int labelId = 0;
    protected long labelCount = 0;
    protected static final int MAX_LABEL_ID = 65535;

    // ---------

    protected URL getResource(final String name) {
        return StarDist2DBase.class.getClassLoader().getResource(name);
    }

    protected boolean showError(String msg) {
        ui.showDialog(msg, MessageType.ERROR_MESSAGE);
        // log.error(msg);
        return false;
    }

    // ---------

    protected void export(String outputType, Candidates polygons, int framePosition, long numFrames, String roiPosition) {
        switch (outputType) {
        case Opt.OUTPUT_ROI_MANAGER:
            exportROIs(polygons, framePosition, numFrames, roiPosition);
            break;
        case Opt.OUTPUT_LABEL_IMAGE:
            exportLabelImage(polygons, framePosition);
            break;
        case Opt.OUTPUT_BOTH:
            exportROIs(polygons, framePosition, numFrames, roiPosition);
            exportLabelImage(polygons, framePosition);
            break;
        case Opt.OUTPUT_POLYGONS:
            exportPolygons(polygons);
            break;
        default:
            showError(String.format("Invalid %s \"%s\"", Opt.OUTPUT_TYPE, outputType));
        }
    }

    protected void exportROIs(Candidates polygons, int framePosition, long numFrames, String roiPosition) {
        if (roiManager == null) {
            roiManager = RoiManager.getRoiManager();
            roiManager.reset(); // clear all rois
            // https://github.com/mpicbg-csbd/stardist-imagej/pull/5:
            // when setting the RoiManager to invisible, the position of the ROI will be properly saved
            // -> the issue is in RoiManager.addRoi(), https://github.com/imagej/imagej1/blob/c4950ee1f19a25828e5ac915ef3f74e5aa13a6e2/ij/plugin/frame/RoiManager.java#L419
            roiManager.setVisible(false);            
        }

        for (final int i : polygons.getWinner()) {
            final PolygonRoi polyRoi = polygons.getPolygonRoi(i);
            if (framePosition > 0) setRoiPosition(polyRoi, framePosition, roiPosition);
            roiManager.addRoi(polyRoi);
            if (exportPointRois) {
                final PointRoi pointRoi = polygons.getOriginRoi(i);
                if (framePosition > 0) setRoiPosition(pointRoi, framePosition, roiPosition);
                roiManager.addRoi(pointRoi);
            }
            if (exportBboxRois) {
                final Roi bboxRoi = polygons.getBboxRoi(i);
                if (framePosition > 0) setRoiPosition(bboxRoi, framePosition, roiPosition);
                roiManager.addRoi(bboxRoi);
            }
        }

        // make the RoiManager visible after adding the ROIs
        if (framePosition == 0 || framePosition == numFrames)
            roiManager.setVisible(true);
    }
    
    protected void setRoiPosition(Roi roi, int framePosition, String roiPosition) {
        switch (roiPosition) {
        case Opt.ROI_POSITION_STACK:
            roi.setPosition(framePosition);
            break;
        case Opt.ROI_POSITION_HYPERSTACK:
            roi.setPosition(1, 1, framePosition);
            break;
        default:
            showError(String.format("Invalid %s \"%s\"", Opt.ROI_POSITION, roiPosition));
        }
    }

    protected void exportLabelImage(Candidates polygons, int framePosition) {
        if (labelImage == null)
            labelImage = createLabelImage();
        if (framePosition > 0)
            labelImage.setT(framePosition);
        final ImageProcessor ip = labelImage.getProcessor();
        final List<Integer> winner = polygons.getWinner();
        final int numWinners = winner.size();
        // winners are ordered by score -> draw from last to first to give priority to higher scores in case of overlaps
        for (int i = numWinners-1; i >= 0; i--) {
            final PolygonRoi polyRoi = polygons.getPolygonRoi(winner.get(i));
            ip.setColor(1 + ((labelId + i) % MAX_LABEL_ID));
            ip.fill(polyRoi);
        }
        labelCount += numWinners;
        labelId = (labelId + numWinners) % MAX_LABEL_ID;
    }

    abstract protected void exportPolygons(Candidates polygons);

    abstract protected ImagePlus createLabelImage();

    protected Dataset labelImageToDataset(String outputType) {
        if (outputType.equals(Opt.OUTPUT_LABEL_IMAGE) || outputType.equals(Opt.OUTPUT_BOTH)) {
            if (labelCount > MAX_LABEL_ID) {
                log.error(String.format("Found more than %d segments -> label image does contain some repetitive IDs.\n(\"%s\" output instead does not have this problem).", MAX_LABEL_ID, Opt.OUTPUT_ROI_MANAGER));
            }
            // IJ.run(labelImage, "glasbey inverted", "");
            final boolean isTimelapse = labelImage.getNFrames() > 1;
            final Img labelImg = (Img) ImageJFunctions.wrap(labelImage);
            final AxisType[] axes = isTimelapse ? new AxisType[]{Axes.X, Axes.Y, Axes.TIME} : new AxisType[]{Axes.X, Axes.Y};
            return Utils.raiToDataset(dataset, Opt.LABEL_IMAGE, labelImg, axes);
        } else {
            return null;
        }

    }
}
