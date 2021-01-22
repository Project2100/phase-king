
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Phase King implementation: monitor
 *
 * @author Project2100
 */
public class Monitor {

    static {
        // This thread should be "main"
        Thread.currentThread().setUncaughtExceptionHandler((thread, exception) -> {
            Logger.getLogger(Monitor.class.getName()).log(Level.SEVERE, "Uncaught exception in thread: " + thread.getName(), exception);
            System.exit(1);
        });
    }
    
    /**
     * Number of nodes
     */
    private static int nodeCount;

    /**
     * How many nodes can go awry before the protocol fails
     */
    private static int maxByzantine;

    public static void main(String[] args) throws IOException, InterruptedException {
        
        // Read and validate options
        int port = -1;
        String coordName = "";
        for (int i = 0; i < args.length; i++) switch (args[i]) {
            case "-n" -> {
                i++;
                if (i == args.length) throw new RuntimeException("Missing node count");
                else {
                    nodeCount = Integer.valueOf(args[i]);
                    maxByzantine = (int) (Math.ceil(nodeCount / 4) - 1);
                }
            }
            case "-c" -> {
                i++;
                if (i == args.length) throw new RuntimeException("Missing coordinator hostname");
                else coordName = args[i];
            }
            case "-p" -> {
                i++;
                if (i == args.length) throw new RuntimeException("Missing TCP port");
                else port = Integer.valueOf(args[i]);
            }
            default -> throw new RuntimeException("Unrecognized option: " + args[i]);
        }
        if (coordName.equals("")) {
            System.out.println("Please provide the coordinator hostname");
            return;
        }
        if (port <= 0) {
            System.out.println("Please provide a valid TCP port");
            return;
        }
        if (nodeCount > 127) {
            System.out.println("More than 127 nodes break this impl. Exiting");
            return;
        }
        System.out.println("Maximum byzantines: " + maxByzantine + ", tiebreaking threshold: " + (nodeCount / 2 + maxByzantine));
        
        
        Socket coordinator = null;
        // Connect to the coordinator
        int retries = 0;
        while (true) try {
            // This call is blocking, or else it throws
            // Also, the ID isn't received until all other peers connect to the coordinator
            coordinator = new Socket(InetAddress.getByName(coordName), port);
            break;
        }
        catch (IOException ex) {
            if (coordinator == null && retries < 3) {
                System.out.println("Failed connecting to coordinator, retrying in two seconds...");
                retries++;
                // Best known way to handle the containers' concurrent startup, for now... "depends_on" option isn't a sure-fire solution, but helps a lot
                Thread.sleep(2000);
            }
            else throw ex;
        }
        
        System.out.println("Connected");
        
        coordinator.close();
        
    }
    
    
}
