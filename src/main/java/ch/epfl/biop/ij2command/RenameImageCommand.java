package ch.epfl.biop.ij2command;

import ij.ImagePlus;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Rename Image")
public class RenameImageCommand implements Command{
    @Parameter(type = ItemIO.BOTH)
    ImagePlus input_imp;
    @Parameter
    String title;

    @Override
    public void run() {
        //String title = input_imp.getTitle();
        input_imp.setTitle(title);
    }
}
