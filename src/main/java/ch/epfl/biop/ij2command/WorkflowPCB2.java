package ch.epfl.biop.ij2command;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.Roi;
import ij.io.FileInfo;
import ij.measure.ResultsTable;
import ij.plugin.Duplicator;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Workflow PCB 2")
public class WorkflowPCB2 implements Command{

    //Initialize starting popup window
    @Parameter(label = "Channel's ID")
    int chID = 1;

    @Parameter(label = "Image path")
    File imp_path;

    @Parameter
    Boolean showImage = false;

    @Override
    public void run() {
        IJ.run("Close All","");

        // Open the image
        ImagePlus imp = IJ.openImage(imp_path.getAbsolutePath());
        if(showImage) imp.show();

        //duplicate channel
        ImagePlus dapiCh_imp = new Duplicator().run(imp,chID,chID,1,1,1,1);
        if(showImage) dapiCh_imp.show();

        //get a binary image
        ImagePlus dapiCh_imp_final = process_image(dapiCh_imp.duplicate());

        // get denoised connected components
        RoiManager rm = new RoiManager();
        ResultsTable rt = ResultsTable.getResultsTable();

        ArrayList<Roi> filteredRois = get_connected_components(rm, dapiCh_imp_final);

        // Create output folder
        String resultFolderpath = create_Folder(imp,"output_java");
        for (int i = 1; i <= imp.getNChannels(); i++) {
            imp.setPosition(i,1,1);
            rm.runCommand(imp,"Measure");
            ImagePlus ch_imp = new Duplicator().run(imp,i,i,1,1,1,1);
            // from the ROIs get the Area measurement map
            ImagePlus mean_imp = R2M(ch_imp,"Mean",rm);
            //ImagePlus mean_imp = cds.run(Rois2MeasurementMap, false , "imp" , ch_imp , "rm", rm, "column_name", "Mean").get().getOutput("results_imp");
            mean_imp.setTitle("c"+i+"_"+ imp.getTitle());
            IJ.saveAsTiff(mean_imp,resultFolderpath+ File.separator +mean_imp.getTitle());
            IJ.saveAs(rt.getTitle(),resultFolderpath+ File.separator +mean_imp.getTitle()+"_Table.csv");
            rt.reset();
        }
    }

    private ImagePlus process_image(ImagePlus imp){
        // filtering
        IJ.run(imp, "Median...", "radius=3");

        // thresholding
        IJ.setAutoThreshold(imp, "Triangle dark");
        Prefs.blackBackground = true;
        IJ.run(imp, "Convert to Mask", "");

        // morphological improvements
        IJ.run(imp, "Open", "");
        IJ.run(imp, "Fill Holes", "");

        return imp;
    }

    private ArrayList<Roi> get_connected_components (RoiManager rm, ImagePlus imp){
        // analyze connected components
        IJ.run(imp, "Analyze Particles...", "clear add");

        // get Rois from Roi manager
        Roi[] rois = rm.getRoisAsArray();
        IJ.log(""+rois.length);

        // delete Rois in the Roi manager
        rm.reset();
        ArrayList<Roi> filtered_Rois = new ArrayList<Roi>();
        for (Roi roi : rois){
            if(roi.getStatistics().area > 50) {
                filtered_Rois.add(roi);
                rm.addRoi(roi);
            }
        }
        IJ.log(""+filtered_Rois.size());

        return filtered_Rois;
    }

    private String create_Folder(ImagePlus imp, String folder_name){
        FileInfo fi = imp.getOriginalFileInfo();
        String exportPath = fi.directory;
        String output_path = exportPath + File.separator + folder_name;
        File file = new File(output_path);
        if(!file.exists())
            file.mkdirs();

        return output_path;
    }

    private ImagePlus R2M(ImagePlus imp, String column_name, RoiManager rm){
        // duplicate the imp for the task
        ImagePlus imp2 = imp.duplicate();
        String pattern = "Track-(\\d*):.*";

        // this is a hack in case the input image is a 32-bit
        // indeed without adding it, the pixels outside of ROIs were not at 0 but 3.4e38!
        // TODO find a better way to solve this
        if (imp.getBitDepth()==32) IJ.run(imp2, "16-bit", "");

        // check if it's a stack
        int stackN = imp2.getImageStackSize();
        boolean isStack = false ;
        if (stackN > 1 ) isStack = true ;
        // reset imp2
        for (int i = 0; i < stackN ; i++) {
            imp2.getStack().getProcessor(i + 1).setValue(0.0);
            imp2.getStack().getProcessor(i + 1).fill();
        }
        // convert to 32-bit (because measurements can be float , or negative)
        IJ.run(imp2, "32-bit", "");
        //imp2.show();
        //System.out.println( "##################################################" );
        //System.out.println( column_name );
        Roi[] rois = rm.getRoisAsArray()  ;
        for (int i = 0; i < rois.length; i++) {
            // initiate the filling value
            double filling_value = 0.0;
            //set the position in the stack if necessary
            if ( isStack ) { // set position on both imp (for stats) and imp2 to set values
                imp.setPosition( rois[i].getPosition() );
                imp2.setPosition( rois[i].getPosition() );
            }
            // and set the ROI
            ImageProcessor ip = imp.getProcessor();
            ip.setRoi( rois[i]);
            ImageProcessor ip2 = imp2.getProcessor();
            ip2.setRoi( rois[i]);
            // so we can get Stats
            ImageStatistics ip_stats = ip.getStatistics() ;
            // from user choice
            switch (column_name) {
                case "Area" :
                    filling_value = ip_stats.area ;
                    break;
                case "Angle" :
                    filling_value = ip_stats.angle;
                    break;
                // the Angle measure is based on horizontal,
                // the AngleVert measure is based on vertical (substracting 90 )
                case "AngleVert" :
                    filling_value = ip_stats.angle - 90 ;
                    break;
                case "AR" :
                    filling_value = ip_stats.major / ip_stats.minor;
                    break;
                case "Circ." :   // 4 x  pi x area / perimeter^2
                    filling_value = 4*Math.PI * ip_stats.area / Math.pow(rois[i].getLength() , 2 );
                    break;
                case "Major":
                    filling_value = ip_stats.major;
                    break;
                case "Minor" :
                    filling_value = ip_stats.minor;
                    break;
                case "Mean" :
                    filling_value = ip_stats.mean;
                    break;
                case "Median" :
                    filling_value = ip_stats.median;
                    break;
                case "Mode" :
                    filling_value = ip_stats.dmode;
                    break;
                case "Min" :
                    filling_value = ip_stats.min;
                    break;
                case "Max" :
                    filling_value = ip_stats.max;
                    break;
                case "Perim.":
                    filling_value = rois[i].getLength();
                    break;
                case "Pattern":
                    // roi_name follows the model Track-0001:Frame-0001 ...
                    // which corresponds to an output from a custom script making use of TrackMate, to link rois.
                    // Values will be the index of the track (hijacking Rois2Measurements to make a  Rois2Labels)
                    String roi_name = rois[i].getName();
                    Pattern r = Pattern.compile(pattern);
                    Matcher m = r.matcher(roi_name);
                    if (m.find( )) {
                        String group = m.group(1);
                        try {
                            filling_value = Float.parseFloat(group);
                        } catch (Exception e ){
                            System.err.println("Issue with your pattern! Can't get a numerical value from it");
                            e.printStackTrace();
                            filling_value = 0.0;
                        }

                    }
                    break;
                case "xCenterOfMass":
                    filling_value = ip_stats.xCenterOfMass;
                    break;
                case "yCenterOfMass":
                    filling_value = ip_stats.yCenterOfMass;
                    break;
            }
            //System.out.println( filling_value );
            ip2.setValue( filling_value );
            ip2.fill( rois[i] );
            imp2.setProcessor(ip2);
        }

        imp2.setTitle(column_name +"_Image");
        imp2.setRoi(0,0, imp2.getWidth(),imp2.getHeight() );
        return imp2;
    }
}
