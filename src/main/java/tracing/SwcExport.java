package tracing;

/**
 * Created by chrism on 09/12/2016.
 */
public class SwcExport {
    public static void main(String[] args) {
        for (String fn: args) {
            System.out.println(fn);
            PathAndFillManager manager = PathAndFillManager.createFromTracesFile(fn);
            String prefix = fn;
            if(fn.contains(".")) {
                prefix = fn.substring(0, fn.lastIndexOf('.'));
            }
            manager.exportAllAsSWC(prefix);
        }
    }
}
