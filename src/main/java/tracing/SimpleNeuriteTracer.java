/* -*- mode: java; c-basic-offset: 8; indent-tabs-mode: t; tab-width: 8 -*- */

/* Copyright 2006, 2007, 2008, 2009, 2010, 2011 Mark Longair */

/*
  This file is part of the ImageJ plugin "Simple Neurite Tracer".

  The ImageJ plugin "Simple Neurite Tracer" is free software; you
  can redistribute it and/or modify it under the terms of the GNU
  General Public License as published by the Free Software
  Foundation; either version 3 of the License, or (at your option)
  any later version.

  The ImageJ plugin "Simple Neurite Tracer" is distributed in the
  hope that it will be useful, but WITHOUT ANY WARRANTY; without
  even the implied warranty of MERCHANTABILITY or FITNESS FOR A
  PARTICULAR PURPOSE.  See the GNU General Public License for more
  details.

  In addition, as a special exception, the copyright holders give
  you permission to combine this program with free software programs or
  libraries that are released under the Apache Public License.

  You should have received a copy of the GNU General Public License
  along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package tracing;

import amira.AmiraMeshDecoder;
import amira.AmiraParameters;
import client.ArchiveClient;
import features.ComputeCurvatures;
import features.GaussianGenerationCallback;
import features.SigmaPalette;
import features.TubenessProcessor;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.gui.ImageRoi;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.gui.StackWindow;
import ij.gui.YesNoCancelDialog;
import ij.io.FileInfo;
import ij.io.OpenDialog;
import ij.plugin.ZProjector;
import ij.plugin.frame.RoiManager;
import ij.process.ByteProcessor;
import ij.text.TextWindow;
import ij3d.Content;
import ij3d.Image3DUniverse;
import ij3d.MeshMaker;

import java.awt.Color;
import java.awt.Component;
import java.awt.event.KeyListener;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.scijava.util.VersionUtils;
import org.scijava.vecmath.Color3f;
import org.scijava.vecmath.Point3d;
import org.scijava.vecmath.Point3f;

import stacks.ThreePanes;

/* Note on terminology:

   "traces" files are made up of "paths".  Paths are non-branching
   sequences of adjacent points (including diagonals) in the image.
   Branches and joins are supported by attributes of paths that
   specify that they begin on (or end on) other paths.

*/

public class SimpleNeuriteTracer extends ThreePanes
	implements SearchProgressCallback, GaussianGenerationCallback, PathAndFillListener {

	public static final String PLUGIN_VERSION = getVersion();
	protected static final boolean verbose = false;

	protected static final int DISPLAY_PATHS_SURFACE = 1;
	protected static final int DISPLAY_PATHS_LINES = 2;
	protected static final int DISPLAY_PATHS_LINES_AND_DISCS = 3;

	protected static final String startBallName = "Start point";
	protected static final String targetBallName = "Target point";
	protected static final int ballRadiusMultiplier = 5;

	protected PathAndFillManager pathAndFillManager;

	protected boolean use3DViewer;
	protected Image3DUniverse univ;
	protected Content imageContent;

	volatile protected boolean unsavedPaths = false;

	public boolean pathsUnsaved() {
		return unsavedPaths;
	}

	private static String getVersion() {
		return VersionUtils.getVersion(tracing.SimpleNeuriteTracer.class);
	}

	public PathAndFillManager getPathAndFillManager() {
		return pathAndFillManager;
	}

	public InteractiveTracerCanvas getXYCanvas() {
		return xy_tracer_canvas;
	}

	public String stripExtension(String filename) {
		int lastDot=filename.lastIndexOf(".");
		if( lastDot > 0 )
			return filename.substring(0, lastDot);
		else
			return null;
	}

	/* Just for convenience, keep casted references to the
	   superclass's InteractiveTracerCanvas objects: */

	protected InteractiveTracerCanvas xy_tracer_canvas;
	protected InteractiveTracerCanvas xz_tracer_canvas;
	protected InteractiveTracerCanvas zy_tracer_canvas;

	public ImagePlus getImagePlus() {
		return xy;
	}

	public double getLargestDimension() {
		return Math.max( x_spacing * width,
				 Math.max( y_spacing * height,
					   z_spacing * depth ));
	}

	public double getStackDiagonalLength() {
		return Math.sqrt( (x_spacing * width) * (x_spacing * width) +
				  (y_spacing * height) * (y_spacing * height) +
				  (z_spacing * depth) * (z_spacing * depth) );
	}

	/* This overrides the method in ThreePanes... */

	@Override
	public InteractiveTracerCanvas createCanvas( ImagePlus imagePlus, int plane ) {
		return new InteractiveTracerCanvas( imagePlus, this, plane, pathAndFillManager );
	}

	public void cancelSearch( boolean cancelFillToo ) {
		if( currentSearchThread != null )
			currentSearchThread.requestStop();
		if( tubularGeodesicsThread != null )
			tubularGeodesicsThread.requestStop();
		endJoin = null;
		endJoinPoint = null;
		if( cancelFillToo && filler != null )
			filler.requestStop();
	}

	public void threadStatus( SearchInterface source, int status ) {
		// Ignore this information.
	}

	public void changeUIState(int newState) {
		resultsDialog.changeState(newState);
	}

	public int getUIState() {
		return resultsDialog.getCurrentState();
	}

	synchronized public void saveFill( ) {

		if( filler != null ) {
			// The filler must be paused while we save to
			// avoid concurrent modifications...

			if (verbose) System.out.println("["+Thread.currentThread()+"] going to lock filler in plugin.saveFill");
			synchronized(filler) {
				if (verbose) System.out.println("["+Thread.currentThread()+"] acquired it");
				if( FillerThread.PAUSED == filler.getThreadStatus() ) {
					// Then we can go ahead and save:
					pathAndFillManager.addFill( filler.getFill() );
					// ... and then stop filling:
					filler.requestStop();
					resultsDialog.changeState( NeuriteTracerResultsDialog.WAITING_TO_START_PATH );
					filler = null;
				} else {
					IJ.error("The filler must be paused before saving the fill.");
				}

			}
			if (verbose) System.out.println("["+Thread.currentThread()+"] left lock on filler");
		}
	}

	synchronized public void discardFill( ) {
		if( filler != null ) {
			synchronized(filler) {
				filler.requestStop();
				resultsDialog.changeState( NeuriteTracerResultsDialog.WAITING_TO_START_PATH );
				filler = null;
			}
		}
	}

	synchronized public void pauseOrRestartFilling( ) {
		if( filler != null ) {
			filler.pauseOrUnpause( );
		}
	}

	protected List<SNTListener> listeners = Collections.synchronizedList(
		new ArrayList<SNTListener>());

	public void addListener(SNTListener listener) {
		listeners.add(listener);
	}

	public void notifyListeners(SNTEvent event) {
		for(SNTListener listener : listeners.toArray(new SNTListener[0])) {
			listener.onEvent(event);
		}
	}

	public boolean anyListeners() {
		return listeners.size() > 0;
	}

	/* Now a couple of callback methods, which get information
	   about the progress of the search. */

	public void finished( SearchInterface source, boolean success ) {

		/* This is called by both filler and currentSearchThread,
		   so distinguish these cases: */

		if( source == currentSearchThread ||
		    source == tubularGeodesicsThread ) {

			removeSphere(targetBallName);

			if( success ) {
				Path result = source.getResult();
				if( result == null ) {
					IJ.error("Bug! Succeeded, but null result.");
					return;
				}
				if( endJoin != null ) {
					result.setEndJoin( endJoin, endJoinPoint );
				}
				setTemporaryPath( result );

				resultsDialog.changeState(NeuriteTracerResultsDialog.QUERY_KEEP);

			} else {

				resultsDialog.changeState(NeuriteTracerResultsDialog.PARTIAL_PATH);
			}

			// Indicate in the dialog that we've finished...

			if (source == currentSearchThread) {
				currentSearchThread = null;
			}

		}

		removeThreadToDraw( source );
		repaintAllPanes();

	}

	public void pointsInSearch( SearchInterface source, int inOpen, int inClosed ) {
		// Just use this signal to repaint the canvas, in case there's
		// been no mouse movement.
		repaintAllPanes();
	}

	/* FIXME, just for synchronization - replace this with
	   synchronization on the object it protects: */

	protected String nonsense = "unused";

	/* These member variables control what we're actually doing -
	   whether that's tracing, logging points or displaying values
	   of the Hessian at particular points.  Currently we only
	   support tracing, support for the others has been
	   removed. */

	protected boolean setupLog = false;
	protected boolean setupEv = false;
	protected boolean setupTrace = false;
	protected boolean setupPreprocess = false;

	/* If we're timing out the searches (probably not any longer...) */

	volatile protected boolean setupTimeout = false;
	volatile protected float   setupTimeoutValue = 0.0f;

	/* For the original file info - needed for loading the
	   corresponding labels file and checking if a "tubes.tif"
	   file already exists: */

	public FileInfo file_info;

	protected int width, height, depth;

	public void justDisplayNearSlices( boolean value, int eitherSide ) {

		xy_tracer_canvas.just_near_slices = value;
		if( ! single_pane ) {
			xz_tracer_canvas.just_near_slices = value;
			zy_tracer_canvas.just_near_slices = value;
		}

		xy_tracer_canvas.eitherSide = eitherSide;
		if( ! single_pane ) {
			xz_tracer_canvas.eitherSide = eitherSide;
			zy_tracer_canvas.eitherSide = eitherSide;
		}

		repaintAllPanes();

	}

	public void setCrosshair( double new_x, double new_y, double new_z ) {

		xy_tracer_canvas.setCrosshairs( new_x, new_y, new_z, true );
		if( ! single_pane ) {
			xz_tracer_canvas.setCrosshairs( new_x, new_y, new_z, true );
			zy_tracer_canvas.setCrosshairs( new_x, new_y, new_z, true );
		}

	}

	protected String [] materialList;
	byte [][] labelData;

	synchronized public void loadLabelsFile( String path ) {

		AmiraMeshDecoder d=new AmiraMeshDecoder();

		if( ! d.open(path) ) {
			IJ.error("Could not open the labels file '"+path+"'");
			return;
		}

		ImageStack stack = d.getStack();

		ImagePlus labels = new ImagePlus( "Label file for Tracer", stack );

		if( (labels.getWidth() != width) ||
		    (labels.getHeight() != height) ||
		    (labels.getStackSize() != depth) ) {
			IJ.error("The size of that labels file doesn't match the size of the image you're tracing.");
			return;
		}

		// We need to get the AmiraParameters object for that image...

		AmiraParameters parameters = d.parameters;

		materialList = parameters.getMaterialList();

		labelData = new byte[depth][];
		for( int z = 0; z < depth; ++z ) {
			labelData[z] = (byte []) stack.getPixels( z + 1 );
		}

	}

	synchronized public void loadLabels( ) {

		String fileName;
		String directory;

		if( file_info != null ) {

			fileName = file_info.fileName;
			directory = file_info.directory;

			File possibleLoadFile = new File(directory,fileName+".labels");

			String path = possibleLoadFile.getPath();

			if(possibleLoadFile.exists()) {

				YesNoCancelDialog d = new YesNoCancelDialog( IJ.getInstance(),
									     "Confirm",
									     "Load the default labels file?\n("+path+")" );

				if( d.yesPressed() ) {

					loadLabelsFile(path);

					return;

				} else if( d.cancelPressed() ) {

					return;

				}
			}
		}

		//  Presumably "No" was pressed...

		OpenDialog od;

		od = new OpenDialog("Select labels file...",
				    null,
				    null );

		fileName = od.getFileName();
		directory = od.getDirectory();

		if( fileName != null ) {

			loadLabelsFile( directory + fileName );
			return;
		}

	}

	volatile boolean loading = false;

	synchronized public void loadTracings( ) {

		loading = true;

		String fileName = null;
		String directory = null;

		if( file_info != null ) {

			fileName = file_info.fileName;
			directory = file_info.directory;

			File possibleLoadFile = null;

			int dotIndex = fileName.lastIndexOf(".");
			if( dotIndex >= 0 ) {
				possibleLoadFile = new File(directory,fileName.substring(0,dotIndex)+".traces");

				String path = possibleLoadFile.getPath();

				if(possibleLoadFile.exists()) {

					YesNoCancelDialog d = new YesNoCancelDialog( IJ.getInstance(),
										     "Confirm",
										     "Load the default traces file?\n("+path+")" );

					if( d.yesPressed() ) {

						if( pathAndFillManager.loadGuessingType(path) )
							unsavedPaths = false;

						Prefs.set("tracing.Simple_Neurite_Tracer.lastTracesLoadDirectory",directory);
						Prefs.savePreferences();

						loading = false;
						return;

					} else if( d.cancelPressed() ) {

						loading = false;
						return;

					}
				}
			}
		}

		directory = Prefs.get("tracing.Simple_Neurite_Tracer.lastTracesLoadDirectory", null);
		if( directory == null && file_info != null && file_info.directory != null )
			directory = file_info.directory;

		//  Presumably "No" was pressed...

		OpenDialog od;

		od = new OpenDialog("Select .traces or .(e)swc file...",
				    directory,
				    null );

		fileName = od.getFileName();
		directory = od.getDirectory();

		if( fileName != null ) {

			File chosenFile = new File( directory, fileName );
			if( ! chosenFile.exists() ) {
				IJ.error("The file '"+chosenFile.getAbsolutePath()+"' didn't exist");
				loading = false;
				return;
			}

			Prefs.set("tracing.Simple_Neurite_Tracer.lastTracesLoadDirectory",directory);
			Prefs.savePreferences();

			int guessedType = PathAndFillManager.guessTracesFileType(chosenFile.getAbsolutePath());

			switch (guessedType) {
			case PathAndFillManager.TRACES_FILE_TYPE_SWC:
			{
				SWCImportOptionsDialog swcImportDialog = new SWCImportOptionsDialog(
					"SWC import options for "+chosenFile.getName());
				// FIXME: pop up a dialog to ask about options:
				// .. and then call the full importSWC.=:
				if( swcImportDialog.succeeded &&
				    pathAndFillManager.importSWC(
					    chosenFile.getAbsolutePath(),
					    swcImportDialog.getIgnoreCalibration(),
					    swcImportDialog.getXOffset(),
					    swcImportDialog.getYOffset(),
					    swcImportDialog.getZOffset(),
					    swcImportDialog.getXScale(),
					    swcImportDialog.getYScale(),
					    swcImportDialog.getZScale(),
					    swcImportDialog.getReplaceExistingPaths() ) )
					unsavedPaths = false;
				break;
			}
			case PathAndFillManager.TRACES_FILE_TYPE_COMPRESSED_XML:
				if( pathAndFillManager.loadCompressedXML( chosenFile.getAbsolutePath() ) )
					unsavedPaths = false;
				break;
			case PathAndFillManager.TRACES_FILE_TYPE_UNCOMPRESSED_XML:
				if( pathAndFillManager.loadUncompressedXML( chosenFile.getAbsolutePath() ) )
					unsavedPaths = false;
				break;
			default:
				IJ.error("The file '"+chosenFile.getAbsolutePath()+"' was of unknown type ("+guessedType+")");
				break;
			}
		}

		loading = false;
	}

	public void mouseMovedTo( double x_in_pane, double y_in_pane, int in_plane, boolean shift_key_down, boolean join_modifier_down ) {

		double x, y, z;

		double [] pd = new double[3];
		findPointInStackPrecise( x_in_pane, y_in_pane, in_plane, pd );
		x = pd[0];
		y = pd[1];
		z = pd[2];

		if( join_modifier_down && pathAndFillManager.anySelected() ) {

			PointInImage pointInImage = pathAndFillManager.nearestJoinPointOnSelectedPaths( x, y, z );
			if( pointInImage != null ) {
				x = pointInImage.x / x_spacing;
				y = pointInImage.y / y_spacing;
				z = pointInImage.z / z_spacing;
			}
		}

		int ix = (int)Math.round(x);
		int iy = (int)Math.round(y);
		int iz = (int)Math.round(z);

		double x_scaled = ix * x_spacing;
		double y_scaled = iy * y_spacing;
		double z_scaled = iz * z_spacing;

		if( shift_key_down )
			setSlicesAllPanes( ix, iy, iz );

		if( (xy_tracer_canvas != null) &&
		    ((xz_tracer_canvas != null) || single_pane) &&
		    ((zy_tracer_canvas != null) || single_pane) ) {


			String statusMessage = "world: ("+x_scaled+","+y_scaled+","+z_scaled+") image: ("+ix+","+iy+","+iz+")";
			setCrosshair( x, y, z );
			if( labelData != null ) {

				byte b = labelData[iz][iy*width+ix];
				int m = b & 0xFF;

				String material = materialList[m];
				statusMessage += ", material: " + material;
			}
			IJ.showStatus(statusMessage);

			repaintAllPanes( ); // Or the crosshair isn't updated....
		}

		if( filler != null ) {
			synchronized (filler) {
				float distance = filler.getDistanceAtPoint(ix,iy,iz);
				resultsDialog.showMouseThreshold(distance);
			}
		}
	}

	volatile boolean lastStartPointSet = false;

	int last_start_point_x;
	int last_start_point_y;
	int last_start_point_z;

	Path endJoin;
	PointInImage endJoinPoint;

	/* If we've finished searching for a path, but the user hasn't
	   confirmed that they want to keep it yet, temporaryPath is
	   non-null and holds the Path we just searched out. */

	// Any method that deals with these two fields should be synchronized.

	Path temporaryPath = null;
	Path currentPath = null;

	// When we set temporaryPath, we also want to update the display:

	synchronized public void setTemporaryPath( Path path ) {

		Path oldTemporaryPath = this.temporaryPath;

		xy_tracer_canvas.setTemporaryPath( path );
		if( ! single_pane ) {
			zy_tracer_canvas.setTemporaryPath( path );
			xz_tracer_canvas.setTemporaryPath( path );
		}

		temporaryPath = path;

		if( temporaryPath != null )
			temporaryPath.setName("Temporary Path");
		if( use3DViewer ) {


			if( oldTemporaryPath != null ) {
				oldTemporaryPath.removeFrom3DViewer(univ);
			}
			if( temporaryPath != null )
				temporaryPath.addTo3DViewer(univ,Color.BLUE,null);
		}
	}

	synchronized public void setCurrentPath( Path path ) {

		Path oldCurrentPath = this.currentPath;

		xy_tracer_canvas.setCurrentPath( path );
		if( ! single_pane ) {
			zy_tracer_canvas.setCurrentPath( path );
			xz_tracer_canvas.setCurrentPath( path );
		}

		currentPath = path;
		if( currentPath != null )
			currentPath.setName("Current Path");

		if( use3DViewer ) {
			if( oldCurrentPath != null ) {
				oldCurrentPath.removeFrom3DViewer(univ);
			}
			if( currentPath != null )
				currentPath.addTo3DViewer(univ,Color.RED,null);
		}
	}

	synchronized public Path getCurrentPath( ) {
		return currentPath;
	}

	/* pathUnfinished indicates that we have started to create a
	   path, but not yet finished it (in the sense of moving on to
	   a new path with a differen starting point.)  FIXME: this
	   may be redundant - check that.
	*/

	volatile boolean pathUnfinished = false;

	public void setPathUnfinished( boolean unfinished ) {

		this.pathUnfinished = unfinished;
		xy_tracer_canvas.setPathUnfinished( unfinished );
		if( ! single_pane ) {
			zy_tracer_canvas.setPathUnfinished( unfinished );
			xz_tracer_canvas.setPathUnfinished( unfinished );
		}
	}

	void addThreadToDraw( SearchInterface s ) {
		xy_tracer_canvas.addSearchThread(s);
		if( ! single_pane ) {
			zy_tracer_canvas.addSearchThread(s);
			xz_tracer_canvas.addSearchThread(s);
		}
	}

	void removeThreadToDraw( SearchInterface s ) {
		xy_tracer_canvas.removeSearchThread(s);
		if( ! single_pane ) {
			zy_tracer_canvas.removeSearchThread(s);
			xz_tracer_canvas.removeSearchThread(s);
		}
	}

	int [] selectedPaths = null;

	/* Create a new 8 bit ImagePlus of the same dimensions as this
	   image, but with values set to either 255 (if there's a point
	   on a path there) or 0 */

	synchronized public ImagePlus makePathVolume( ) {

		byte [][] snapshot_data = new byte[depth][];

		for( int i = 0; i < depth; ++i )
			snapshot_data[i] = new byte[width*height];

		pathAndFillManager.setPathPointsInVolume( snapshot_data, width, height, depth );

		ImageStack newStack = new ImageStack( width, height );

		for( int i = 0; i < depth; ++i ) {
			ByteProcessor thisSlice = new ByteProcessor( width, height );
			thisSlice.setPixels( snapshot_data[i] );
			newStack.addSlice( null, thisSlice );
		}

		ImagePlus newImp = new ImagePlus( "Paths rendered in a Stack", newStack );
		newImp.setCalibration(xy.getCalibration());
		return newImp;
	}

	/* If non-null, holds a reference to the currently searching thread: */

	TracerThread currentSearchThread;
	TubularGeodesicsTracer tubularGeodesicsThread = null;

	/* Start a search thread looking for the goal in the arguments: */

	synchronized void testPathTo( double world_x, double world_y, double world_z, PointInImage joinPoint ) {

		if( ! lastStartPointSet ) {
			IJ.showStatus( "No initial start point has been set.  Do that with a mouse click." +
				       " (Or a shift-click if the start of the path should join another neurite." );
			return;
		}

		if( temporaryPath != null ) {
			IJ.showStatus( "There's already a temporary path; use 'N' to cancel it or 'Y' to keep it." );
			return;
		}

		double real_x_end, real_y_end, real_z_end;

		int x_end, y_end, z_end;
		if( joinPoint == null ) {
			real_x_end = world_x;
			real_y_end = world_y;
			real_z_end = world_z;
		} else {
			real_x_end = joinPoint.x;
			real_y_end = joinPoint.y;
			real_z_end = joinPoint.z;
			endJoin = joinPoint.onPath;
			endJoinPoint = joinPoint;
		}

		addSphere( targetBallName,
			   real_x_end,
			   real_y_end,
			   real_z_end,
			   Color.BLUE,
			   x_spacing * ballRadiusMultiplier );

		x_end = (int)Math.round( real_x_end / x_spacing );
		y_end = (int)Math.round( real_y_end / y_spacing );
		z_end = (int)Math.round( real_z_end / z_spacing );

		if (tubularGeodesicsTracingEnabled) {

			// Then useful values are:
			// oofFile.getAbsolutePath() - the filename of the OOF file
			// last_start_point_[xyz] - image coordinates of the start point
			// [xyz]_end - image coordinates of the end point

			// [xyz]_spacing

			tubularGeodesicsThread = new TubularGeodesicsTracer(
				oofFile,
				last_start_point_x,
				last_start_point_y,
				last_start_point_z,
				x_end,
				y_end,
				z_end,
				x_spacing,
				y_spacing,
				z_spacing,
				spacing_units);

			addThreadToDraw( tubularGeodesicsThread );

			tubularGeodesicsThread.addProgressListener( this );

			tubularGeodesicsThread.start();

		} else {

			currentSearchThread = new TracerThread(
				xy,
				stackMin,
				stackMax,
				0, // timeout in seconds
				1000, // reportEveryMilliseconds
				last_start_point_x,
				last_start_point_y,
				last_start_point_z,
				x_end,
				y_end,
				z_end,
				true, // reciprocal
				singleSlice,
				(hessianEnabled ? hessian : null),
				resultsDialog.getMultiplier(),
				tubeness,
				hessianEnabled );

			addThreadToDraw( currentSearchThread );

			currentSearchThread.setDrawingColors( Color.CYAN, null );
			currentSearchThread.setDrawingThreshold( -1 );

			currentSearchThread.addProgressListener( this );

			currentSearchThread.start();

		}

		repaintAllPanes();
	}

	synchronized public void confirmTemporary( ) {

		if( temporaryPath == null )
			// Just ignore the request to confirm a path (there isn't one):
			return;

		currentPath.add( temporaryPath );

		PointInImage last = currentPath.lastPoint();
		last_start_point_x = (int)Math.round(last.x / x_spacing);
		last_start_point_y = (int)Math.round(last.y / y_spacing);
		last_start_point_z = (int)Math.round(last.z / z_spacing);

		if( currentPath.endJoins == null ) {
			setTemporaryPath( null );
			resultsDialog.changeState( NeuriteTracerResultsDialog.PARTIAL_PATH );
			repaintAllPanes( );
		} else {
			setTemporaryPath( null );
			// Since joining onto another path for the end must finish the path:
			finishedPath( );
		}

		/* This has the effect of removing the path from the
		   3D viewer and adding it again: */
		setCurrentPath(currentPath);
	}

	synchronized public void cancelTemporary( ) {

		if( ! lastStartPointSet ) {
			IJ.error( "No initial start point has been set yet.  Do that with a mouse click." +
				  " (Or a control-click if the start of the path should join another neurite." );
			return;
		}

		if( temporaryPath == null ) {
			IJ.error( "There's no temporary path to cancel!" );
			return;
		}

		removeSphere( targetBallName );

		if( temporaryPath.endJoins != null ) {
			temporaryPath.unsetEndJoin();
		}

		setTemporaryPath( null );

		endJoin = null;
		endJoinPoint = null;

		resultsDialog.changeState( NeuriteTracerResultsDialog.PARTIAL_PATH );
		repaintAllPanes( );
	}

	synchronized public void cancelPath( ) {

		if( currentPath != null ) {
			if( currentPath.startJoins != null )
				currentPath.unsetStartJoin();
			if( currentPath.endJoins != null )
				currentPath.unsetEndJoin();
		}

		removeSphere( targetBallName );
		removeSphere( startBallName );

		setCurrentPath( null );
		setTemporaryPath( null );

		lastStartPointSet = false;
		setPathUnfinished( false );

		resultsDialog.changeState( NeuriteTracerResultsDialog.WAITING_TO_START_PATH );

		repaintAllPanes();
	}

	synchronized public void finishedPath( ) {

		// Is there an unconfirmed path?  If so, warn people about it...

		if( temporaryPath != null ) {
			IJ.error( "There's an unconfirmed path, need to confirm or cancel it before finishing the path." );
			return;
		}

		if( currentPath == null ) {
			IJ.error("You can't complete a path with only a start point in it.");
			return;
		}

		removeSphere(startBallName);
		removeSphere(targetBallName);

		lastStartPointSet = false;
		setPathUnfinished( false );

		Path savedCurrentPath = currentPath;
		setCurrentPath(null);

		pathAndFillManager.addPath( savedCurrentPath, true );

		unsavedPaths = true;

		// ... and change the state of the UI
		resultsDialog.changeState( NeuriteTracerResultsDialog.WAITING_TO_START_PATH );

		repaintAllPanes( );
	}

	synchronized public void clickForTrace( Point3d p, boolean join ) {
		double x_unscaled = p.x / x_spacing;
		double y_unscaled = p.y / y_spacing;
		double z_unscaled = p.z / z_spacing;
		setSlicesAllPanes( (int)x_unscaled,
				   (int)y_unscaled,
				   (int)z_unscaled );
		clickForTrace( p.x, p.y, p.z, join );
	}

	synchronized public void clickForTrace( double world_x, double world_y, double world_z, boolean join ) {

		PointInImage joinPoint = null;

		if( join ) {
			joinPoint = pathAndFillManager.nearestJoinPointOnSelectedPaths( world_x / x_spacing,
											world_y / y_spacing,
											world_z / z_spacing );
		}

		if( resultsDialog == null )
			return;

		// FIXME: in some of the states this doesn't make sense; check for them:

		if( currentSearchThread != null )
			return;

		if( temporaryPath != null )
			return;

		if( filler != null ) {
			setFillThresholdFrom( world_x, world_y, world_z );
			return;
		}

		if( pathUnfinished ) {
			/* Then this is a succeeding point, and we
			   should start a search. */
			testPathTo( world_x, world_y, world_z, joinPoint );
			resultsDialog.changeState( NeuriteTracerResultsDialog.SEARCHING );
		} else {
			/* This is an initial point. */
			startPath( world_x, world_y, world_z, joinPoint );
			resultsDialog.changeState( NeuriteTracerResultsDialog.PARTIAL_PATH );
		}

	}

	synchronized public void clickForTrace( double x_in_pane_precise, double y_in_pane_precise, int plane, boolean join ) {

		double [] p = new double[3];
		findPointInStackPrecise( x_in_pane_precise, y_in_pane_precise, plane, p );

		double world_x = p[0] * x_spacing;
		double world_y = p[1] * y_spacing;
		double world_z = p[2] * z_spacing;

		clickForTrace( world_x, world_y, world_z, join );
	}

	public void setFillThresholdFrom( double world_x, double world_y, double world_z ) {

		float distance = filler.getDistanceAtPoint( world_x / x_spacing,
							    world_y / y_spacing,
							    world_z / z_spacing );

		setFillThreshold( distance );
	}

	public void setFillThreshold( double distance ) {

		if( distance > 0 ) {

			if (verbose) System.out.println("Setting new threshold of: "+distance);

			resultsDialog.thresholdChanged(distance);

			filler.setThreshold(distance);
		}

	}

	synchronized void startPath( double world_x, double world_y, double world_z, PointInImage joinPoint ) {

		endJoin = null;
		endJoinPoint = null;

		if( lastStartPointSet ) {
			IJ.showStatus( "The start point has already been set; to finish a path press 'F'" );
			return;
		}

		setPathUnfinished( true );
		lastStartPointSet = true;

		Path path = new Path(x_spacing,y_spacing,z_spacing,spacing_units);
		path.setName("New Path");

		Color ballColor;

		double real_last_start_x, real_last_start_y, real_last_start_z;

		if( joinPoint == null ) {
			real_last_start_x = world_x;
			real_last_start_y = world_y;
			real_last_start_z = world_z;
			ballColor = Color.BLUE;
		} else {
			real_last_start_x = joinPoint.x;
			real_last_start_y = joinPoint.y;
			real_last_start_z = joinPoint.z;
			path.setStartJoin( joinPoint.onPath, joinPoint );
			ballColor = Color.GREEN;
		}

		last_start_point_x = (int)Math.round( real_last_start_x / x_spacing );
		last_start_point_y = (int)Math.round( real_last_start_y / y_spacing );
		last_start_point_z = (int)Math.round( real_last_start_z / z_spacing );

		addSphere( startBallName,
			   real_last_start_x,
			   real_last_start_y,
			   real_last_start_z,
			   ballColor,
			   x_spacing * ballRadiusMultiplier );

		setCurrentPath( path );
	}

	protected void addSphere( String name, double x, double y, double z, Color color, double radius ) {
		if( use3DViewer ) {
			List<Point3f> sphere = MeshMaker.createSphere( x,
								       y,
								       z,
								       radius);
			univ.addTriangleMesh( sphere, new Color3f(color), name );
		}
	}

	protected void removeSphere( String name ) {
		if( use3DViewer )
			univ.removeContent(name);
	}

	/* Return true if we have just started a new path, but have
	   not yet added any connections to it, otherwise return
	   false. */

	public boolean justFirstPoint() {
		return pathUnfinished && (currentPath.size() == 0);
	}

	public static String getStackTrace( ) {
		StringWriter sw = new StringWriter();
		new Exception("Dummy Exception for Stack Trace").printStackTrace(new PrintWriter(sw));
		return sw.toString();
	}

	protected double x_spacing = 1;
	protected double y_spacing = 1;
	protected double z_spacing = 1;

	protected String spacing_units = "";

	public void viewFillIn3D( boolean asMask ) {
		ImagePlus imagePlus = filler.fillAsImagePlus( asMask );
		imagePlus.show();
	}

	public void setPositionAllPanes( int x, int y, int z ) {

		xy.setSlice( z + 1 );
		zy.setSlice( x );
		xz.setSlice( y );

	}

	protected int imageType = -1;

	protected byte [][] slices_data_b;
	protected short [][] slices_data_s;
	protected float [][] slices_data_f;

	protected NeuriteTracerResultsDialog resultsDialog;

	volatile protected boolean cancelled = false;

	protected TextWindow helpTextWindow;

	protected boolean singleSlice;

	protected ArchiveClient archiveClient;

	volatile protected float stackMax = Float.MIN_VALUE;
	volatile protected float stackMin = Float.MAX_VALUE;


	public int guessResamplingFactor() {
		if( width == 0 || height == 0 || depth == 0 )
			throw new RuntimeException("Can't call guessResamplingFactor() before width, height and depth are set...");
		/* This is about right for me, but probably should be
		   related to the free memory somehow.  However, those
		   calls are so notoriously unreliable on Java that
		   it's probably not worth it. */
		long maxSamplePoints = 500 * 500 * 100;
		int level = 0;
		while( true ) {
			long samplePoints =
				(long)(width >> level) *
				(long)(height >> level) *
				(long)(depth >> level);
			if( samplePoints < maxSamplePoints )
				return (1 << level);
			++ level;
		}
	}

	public boolean isReady() {
		if( resultsDialog == null )
			return false;
		return resultsDialog.isVisible();
	}

	public void launchPaletteAround( int x, int y, int z ) {

		int either_side = 40;

		int x_min = x - either_side;
		int x_max = x + either_side;
		int y_min = y - either_side;
		int y_max = y + either_side;
		int z_min = z - either_side;
		int z_max = z + either_side;

		int originalWidth = xy.getWidth();
		int originalHeight = xy.getHeight();
		int originalDepth = xy.getStackSize();

		if( x_min < 0 )
			x_min = 0;
		if( y_min < 0 )
			y_min = 0;
		if( z_min < 0 )
			z_min = 0;
		if( x_max >= originalWidth )
			x_max = originalWidth - 1;
		if( y_max >= originalHeight )
			y_max = originalHeight - 1;
		if( z_max >= originalDepth )
			z_max = originalDepth - 1;

		double [] sigmas = new double[9];
		for( int i = 0; i < sigmas.length; ++i ) {
			sigmas[i] = ((i + 1) * getMinimumSeparation()) / 2;
		}

		resultsDialog.changeState( NeuriteTracerResultsDialog.WAITING_FOR_SIGMA_CHOICE );

		SigmaPalette sp = new SigmaPalette();
		sp.setListener( resultsDialog );
		sp.makePalette( xy, x_min, x_max, y_min, y_max, z_min, z_max, new TubenessProcessor(true), sigmas, 256 / resultsDialog.getMultiplier(), 3, 3, z );
	}

	public void startFillerThread( FillerThread filler ) {

		this.filler = filler;

		filler.addProgressListener(this);
		filler.addProgressListener(resultsDialog.fw);

		addThreadToDraw(filler);

		filler.start();

		resultsDialog.changeState(NeuriteTracerResultsDialog.FILLING_PATHS);

	}

	// This should only be assigned to when synchronized on this object
	// (FIXME: check that that is true)
	FillerThread filler = null;

	synchronized public void startFillingPaths( Set<Path> fromPaths ) {

		// currentlyFilling = true;
		resultsDialog.fw.pauseOrRestartFilling.setText("Pause");

		filler = new FillerThread( xy,
					   stackMin,
					   stackMax,
					   false, // startPaused
					   true, // reciprocal
					   0.03f, // Initial threshold to display
					   5000 ); // reportEveryMilliseconds

		addThreadToDraw(filler);

		filler.addProgressListener( this );
		filler.addProgressListener( resultsDialog.fw );

		filler.setSourcePaths( fromPaths );

		resultsDialog.setFillListVisible(true);

		filler.start();

		resultsDialog.changeState(NeuriteTracerResultsDialog.FILLING_PATHS);

	}

	public void setFillTransparent( boolean transparent ) {
		xy_tracer_canvas.setFillTransparent(transparent);
		if( ! single_pane ) {
			xz_tracer_canvas.setFillTransparent(transparent);
			zy_tracer_canvas.setFillTransparent(transparent);
		}
	}

	public double getMinimumSeparation() {
		return Math.min(Math.abs(x_spacing),Math.min(Math.abs(y_spacing),Math.abs(z_spacing)));
	}

	volatile boolean hessianEnabled = false;
	ComputeCurvatures hessian = null;
	/* This variable just stores the sigma which the current
	   'hessian' ComputeCurvatures was / is being calculated
	   (or -1 if 'hessian' is null) ... */
	volatile double hessianSigma = -1;

	public void startHessian() {
		if( hessian == null ) {
			resultsDialog.changeState(NeuriteTracerResultsDialog.CALCULATING_GAUSSIAN);
			hessianSigma = resultsDialog.getSigma();
			hessian = new ComputeCurvatures( xy, hessianSigma, this, true );
			new Thread(hessian).start();
		} else {
			double newSigma = resultsDialog.getSigma();
			if( newSigma != hessianSigma ) {
				resultsDialog.changeState(NeuriteTracerResultsDialog.CALCULATING_GAUSSIAN);
				hessianSigma = newSigma;
				hessian = new ComputeCurvatures( xy, hessianSigma, this, true );
				new Thread(hessian).start();
			}
		}
	}

	// Even better, we might have a "tubeness" file already there.
	// If this is non-null then we found the "tubeness" file
	// (called foo.tubes.tif) on startup and loaded it
	// successfully.

	float [][] tubeness;

	public boolean oofFileAvailable() {
		return oofFile != null;
	}

	/* If there appears to be a local file called
	   <image-basename>.oof.nrrd then we assume that we can use
	   the Tubular Geodesics tracing method.  This variable null
	   if not such file was found. */

	protected File oofFile = null;
	protected boolean tubularGeodesicsTracingEnabled = false;

	public synchronized void enableTubularGeodesicsTracing( boolean enable ) {
		tubularGeodesicsTracingEnabled = enable;
	}

	public synchronized void enableHessian( boolean enable ) {
		hessianEnabled = enable;
		if( enable ) {
			startHessian();
			resultsDialog.editSigma.setEnabled(false);
			resultsDialog.sigmaWizard.setEnabled(false);
		} else {
			resultsDialog.editSigma.setEnabled(true);
			resultsDialog.sigmaWizard.setEnabled(true);
		}
	}

	public synchronized void cancelGaussian( ) {
		if( hessian != null ) {
			hessian.cancelGaussianGeneration();
		}
	}

	// This is the implementation of GaussianGenerationCallback
	@Override
	public void proportionDone( double proportion ) {
		if( proportion < 0 ) {
			hessianEnabled = false;
			hessian = null;
			hessianSigma = -1;
			resultsDialog.gaussianCalculated(false);
			IJ.showProgress(1.0);
			return;
		} else if( proportion >= 1.0 ) {
			hessianEnabled = true;
			resultsDialog.gaussianCalculated(true);
		}
		IJ.showProgress(proportion);
	}

	/*
	public void getTracings( boolean mineOnly ) {
		boolean result = pathAndFillManager.getTracings( mineOnly, archiveClient );
		if( result )
			unsavedPaths = false;
	}
	*/

	/*
	public void uploadTracings( ) {
		boolean result = pathAndFillManager.uploadTracings( archiveClient );
		if( result )
			unsavedPaths = false;
	}
	*/

	public static boolean haveJava3D() {
		ClassLoader loader = IJ.getClassLoader();
		if (loader == null)
			throw new RuntimeException("IJ.getClassLoader() failed (!)");
		try {
			Class<?> c = loader.loadClass("ij3d.ImageWindow3D");
			/* In fact the documentation says that this
			   should throw an exception and not return
			   null, but just in case: */
			return c != null;
		} catch( Exception e ) {
			return false;
		}
	}

	public void showCorrespondencesTo( File tracesFile, Color c, double maxDistance ) {

		PathAndFillManager pafmTraces = new PathAndFillManager(
			width, height, depth,
			(float)x_spacing, (float)y_spacing, (float)z_spacing,
			spacing_units );

		/* FIXME: may well want to odd SWC options here, which isn't
		   done with the "loadGuessingType" method: */
		if( ! pafmTraces.loadGuessingType( tracesFile.getAbsolutePath() ) ) {
			IJ.error("Failed to load traces from: "+tracesFile.getAbsolutePath());
			return;
		}

		List<Point3f> linePoints = new ArrayList<Point3f>();

		// Now find corresponding points from the first one, and draw lines to them:
		ArrayList< NearPoint > cp = pathAndFillManager.getCorrespondences( pafmTraces, 2.5 );
		int done = 0;
		for( NearPoint np : cp ) {
			if( np != null ) {
				// System.out.println("Drawing:");
				// System.out.println(np.toString());

				linePoints.add(new Point3f((float)np.nearX,
							   (float)np.nearY,
							   (float)np.nearZ));
				linePoints.add(new Point3f((float)np.closestIntersection.x,
							   (float)np.closestIntersection.y,
							   (float)np.closestIntersection.z));

				String ballName = univ.getSafeContentName("ball "+done);
				List<Point3f> sphere = MeshMaker.createSphere( np.nearX,
								      np.nearY,
								      np.nearZ,
								      Math.abs(x_spacing/2) );
				univ.addTriangleMesh( sphere, new Color3f(c), ballName );
			}
			++done;
		}
		univ.addLineMesh( linePoints, new Color3f(Color.red), "correspondences", false );

		for( int pi = 0; pi < pafmTraces.size(); ++pi ) {
			Path p = pafmTraces.getPath(pi);
			if( p.getUseFitted() )
				continue;
			else
				p.addAsLinesTo3DViewer(univ,c,null);
		}
		// univ.resetView();
	}

	volatile private boolean showOnlySelectedPaths;

	public void setShowOnlySelectedPaths(boolean showOnlySelectedPaths) {
		this.showOnlySelectedPaths = showOnlySelectedPaths;
		update3DViewerContents();
		repaintAllPanes();
	}

	public void addPathsToManager(final RoiManager rm) {
		if (rm != null) {
			final Overlay overlay = new Overlay();
			addAllPathsToOverlay(overlay);
			for (final Roi path : overlay.toArray())
				rm.addRoi(path);
			rm.runCommand("sort");
		}
	}

	public void addAllPathsToOverlay(final Overlay overlay) {
		if (overlay != null && pathAndFillManager != null) {
			for (int i = 0; i < pathAndFillManager.size(); ++i) {
				final Path p = pathAndFillManager.getPath(i);
				if (p == null)
					continue;
				if (p.fittedVersionOf != null)
					continue;
				// Prefer fitted version when drawing path
				final Path drawPath = (p.useFitted) ? p.fitted : p;
				drawPath.drawPathAsPoints(overlay, deselectedColor);
			}
		}
	}

	public void addPathsToOverlay() {
		if (xy != null)
			addPathsToOverlay(xy, ThreePanes.XY_PLANE);
	}

	public void addPathsToOverlay(final ImagePlus imp, final int plane) {

		Overlay overlay = imp.getOverlay();
		if (overlay == null)
			overlay = new Overlay();

		if (pathAndFillManager != null) {
			for (int i = 0; i < pathAndFillManager.size(); ++i) {

				final Path p = pathAndFillManager.getPath(i);
				if (p == null)
					continue;

				if (p.fittedVersionOf != null)
					continue;

				// If the path suggests using the fitted version, draw that
				// instead
				final Path drawPath = (p.useFitted) ? p.fitted : p;

				Color color = deselectedColor;
				if (pathAndFillManager.isSelected(p))
					color = selectedColor;
				else if (showOnlySelectedPaths)
					continue;
				drawPath.drawPathAsPoints(overlay, color, plane);
			}
			imp.setOverlay(overlay);
		}
	}

	public StackWindow getWindow(final int plane) {
		switch (plane) {
		case ThreePanes.XY_PLANE:
			return xy_window;
		case ThreePanes.XZ_PLANE:
			return (single_pane) ? null : xz_window;
		case ThreePanes.ZY_PLANE:
			return (single_pane) ? null : zy_window;
		default:
			return null;
		}
	}

	public boolean getSinglePane() {
		return single_pane;
	}

	public boolean getShowOnlySelectedPaths() {
		return showOnlySelectedPaths;
	}

	/* Whatever the state of the paths, update the 3D viewer to
	   make sure that they're the right colour, the right version
	   (fitted or unfitted) is being used and whether the path
	   should be displayed at all - it shouldn't if the "Show only
	   selected paths" option is set. */

	public void update3DViewerContents() {
		pathAndFillManager.update3DViewerContents();
	}

	public Image3DUniverse get3DUniverse() {
		return univ;
	}

	public Color3f selectedColor3f = new Color3f( Color.green );
	public Color3f deselectedColor3f = new Color3f( Color.magenta );
	public Color selectedColor = Color.GREEN;
	public Color deselectedColor = Color.MAGENTA;

	public ImagePlus colorImage;

	public void setSelectedColor( Color newColor ) {
		selectedColor = newColor;
		selectedColor3f = new Color3f( newColor );
		repaintAllPanes();
		update3DViewerContents();
	}

	public void setDeselectedColor( Color newColor ) {
		deselectedColor = newColor;
		deselectedColor3f = new Color3f( newColor );
		repaintAllPanes();
		update3DViewerContents();
	}

	/* FIXME: this can be very slow ... Perhaps do it in a
	   separate thread? */
	public void setColorImage( ImagePlus newColorImage ) {
		colorImage = newColorImage;
		update3DViewerContents();
	}

	private int paths3DDisplay = 1;

	public void setPaths3DDisplay( int paths3DDisplay ) {
		this.paths3DDisplay = paths3DDisplay;
		update3DViewerContents();
	}

	public int getPaths3DDisplay( ) {
		return this.paths3DDisplay;
	}

	public void selectPath( Path p, boolean addToExistingSelection ) {
		HashSet<Path> pathsToSelect = new HashSet<Path>();
		if( p.isFittedVersionOfAnotherPath() )
			pathsToSelect.add(p.fittedVersionOf);
		else
			pathsToSelect.add(p);
		if( addToExistingSelection ) {
			pathsToSelect.addAll( resultsDialog.pw.getSelectedPaths() );
		}
		resultsDialog.pw.setSelectedPaths( pathsToSelect, this );
	}

	public Set<Path> getSelectedPaths() {
		if( resultsDialog.pw != null ) {
			return resultsDialog.pw.getSelectedPaths();
		}
		throw new RuntimeException("getSelectedPaths was called when resultsDialog.pw was null");
	}

	@Override
	public void setPathList( String [] newList, Path justAdded, boolean expandAll ) { }

	@Override
	public void setFillList( String [] newList ) { }

	// Note that rather unexpectedly the p.setSelcted calls make sure that
	// the colour of the path in the 3D viewer is right...  (FIXME)
	@Override
	public void setSelectedPaths( HashSet<Path> selectedPathsSet, Object source ) {
		if( source == this )
			return;
		for( int i = 0; i < pathAndFillManager.size(); ++i ) {
			Path p = pathAndFillManager.getPath(i);
			if( selectedPathsSet.contains(p) ) {
				p.setSelected( true );
			} else {
				p.setSelected( false );
			}
		}
	}

	/** This method will remove the existing keylisteners from the
	    component 'c', tells 'firstKeyListener' to call those key
	    listeners if it has not dealt with the key, and then sets
	    'firstKeyListener' as the key listener for 'c' */
	public static void setAsFirstKeyListener(Component c, QueueJumpingKeyListener firstKeyListener) {
		KeyListener [] oldKeyListeners = c.getKeyListeners();
		for( KeyListener kl : oldKeyListeners ) {
			c.removeKeyListener(kl);
		}
		firstKeyListener.addOtherKeyListeners(oldKeyListeners);
		c.addKeyListener(firstKeyListener);
	}

	public void clickAtMaxPoint( int x_in_pane, int y_in_pane, int plane ) {
		int [][] pointsToConsider = findAllPointsAlongLine( x_in_pane, y_in_pane, plane );
		ArrayList<int[]> pointsAtMaximum = new ArrayList<int[]>();
		float currentMaximum = -Float.MAX_VALUE;
		for( int i = 0; i < pointsToConsider.length; ++i ) {
			float v = -Float.MAX_VALUE;
			int [] p = pointsToConsider[i];
			int xyIndex = p[1]*width + p[0];
			switch (imageType) {
			case ImagePlus.GRAY8:
			case ImagePlus.COLOR_256:
				v = 0xFF & slices_data_b[p[2]][xyIndex];
				break;
			case ImagePlus.GRAY16:
				v = slices_data_s[p[2]][xyIndex];
				break;
			case ImagePlus.GRAY32:
				v = slices_data_f[p[2]][xyIndex];
				break;
			default:
				throw new RuntimeException("Unknow image type: "+imageType);
			}
			if( v > currentMaximum ) {
				pointsAtMaximum = new ArrayList<int[]>();
				pointsAtMaximum.add(p);
				currentMaximum = v;
			} else if( v == currentMaximum ) {
				pointsAtMaximum.add(p);
			}
		}
		/* Take the middle of those points, and pretend that
		   was the point that was clicked on. */
		int [] p = pointsAtMaximum.get(pointsAtMaximum.size()/2);

		clickForTrace( p[0] * x_spacing,
			       p[1] * y_spacing,
			       p[2] * z_spacing,
			       false );
	}

	public static final int OVERLAY_OPACITY_PERCENT = 20;
	private static final String OVERLAY_IDENTIFIER = "SNT-MIP-OVERLAY";

	public void showMIPOverlays(boolean show) {
		ArrayList<ImagePlus> allImages = new ArrayList<ImagePlus>();
		allImages.add(xy);
		if( ! single_pane ) {
			allImages.add(xz);
			allImages.add(zy);
		}
		for( ImagePlus imagePlus : allImages ) {
			if (imagePlus == null || imagePlus.getImageStackSize() == 1)
				continue;
			Overlay overlayList = imagePlus.getOverlay();
			if( show ) {

				// Create a MIP project of the stack:
				ZProjector zp = new ZProjector();
				zp.setImage(imagePlus);
				zp.setMethod(ZProjector.MAX_METHOD);
				zp.doProjection();
				ImagePlus overlay = zp.getProjection();

				// Add display it as an overlay.
				// (This logic is taken from OverlayCommands.)
				Roi roi = new ImageRoi(0, 0, overlay.getProcessor());
				roi.setName(OVERLAY_IDENTIFIER);
				((ImageRoi)roi).setOpacity(OVERLAY_OPACITY_PERCENT/100.0);
				if (overlayList==null)
					overlayList = new Overlay();
				overlayList.add(roi);

			} else {
				removeMIPfromOverlay(overlayList);
			}
			imagePlus.setOverlay(overlayList);
		}
	}

	private void removeMIPfromOverlay(Overlay overlay) {
		if (overlay != null && overlay.size() > 0) {
			for (int i = overlay.size() - 1; i >= 0; i--) {
				final String roiName = overlay.get(i).getName();
				if (roiName != null && roiName.equals(OVERLAY_IDENTIFIER)) {
					overlay.remove(i);
					return;
				}
			}
		}
	}

	protected boolean drawDiametersXY = Prefs.get("tracing.Simple_Neurite_Tracer.drawDiametersXY", "false").equals("true");
	public void setDrawDiametersXY(boolean draw) {
		drawDiametersXY = draw;
		Prefs.set("tracing.Simple_Neurite_Tracer.drawDiametersXY", Boolean.toString(draw));
		Prefs.savePreferences();
		repaintAllPanes();
	}

	public boolean getDrawDiametersXY() {
		return drawDiametersXY;
	}

	@Override
	public void closeAndReset() {
		// Dispose xz/zy images unless the user stored some annotations (ROIs)
		// on the image overlay or modified them somehow. In that case, restore
		// them to the user
		if (!single_pane) {
			final ImagePlus[] impPanes = { xz, zy };
			final StackWindow[] winPanes = { xz_window, zy_window };
			for (int i = 0; i < impPanes.length; ++i) {
				final Overlay overlay = impPanes[i].getOverlay();
				removeMIPfromOverlay(overlay);
				if (!impPanes[i].changes && (overlay == null || impPanes[i].getOverlay().size() == 0))
					impPanes[i].close();
				else {
					winPanes[i] = new StackWindow(impPanes[i]);
					removeMIPfromOverlay(overlay);
					impPanes[i].setOverlay(overlay);
				}
			}
		}
		// Restore main view
		final Overlay overlay = (xy == null) ? null : xy.getOverlay();
		if (original_xy_canvas != null && xy != null && xy.getImage() != null) {
			xy_window = new StackWindow(xy, original_xy_canvas);
			removeMIPfromOverlay(overlay);
			xy.setOverlay(overlay);
		}
	}

}
