
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Phase King implementation: single node
 *
 * @author Project2100
 */
public class Node {

    static {
        // This thread should be "main"
        Thread.currentThread().setUncaughtExceptionHandler((thread, exception) -> {
            Logger.getLogger(Node.class.getName()).log(Level.SEVERE, "Uncaught exception in thread: " + thread.getName(), exception);
            System.exit(1);
        });
    }
    
    private static final ThreadLocalRandom RNG = ThreadLocalRandom.current();
    
    /**
     * Node identifier, used for deciding whether the node is king at any given phase
     */
    private static int identifier;

    /**
     * Number of nodes
     */
    private static int nodeCount;

    /**
     * How many nodes can go awry before the protocol fails (maxByz < ceil(n/4))
     */
    private static int maxByzantine;

    /**
     * How many phases are executed, based on the maximum number of Byzantine nodes
     */
    private static int phaseCount;
    
    private static Socket coordinator;
    
    private static Socket[] peers;
    
    private static boolean verbose = false;
    
    
    /**
     * Synchronization routine, with the help of the coordinator
     * 
     * @throws IOException 
     */
    private static void sync(int message) throws IOException {
        coordinator.getOutputStream().write(message);
        coordinator.getInputStream().read();
    }


    /**
     * The procedure:
     *
     * - Join an UDP/IP multicast group by means of a specific IP address
     * - Connect to the coordinator with a standard TCP/IP socket, address is
     * given as argument
     *
     *
     * @param args
     * @throws IOException
     * @throws InterruptedException
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        
        // Read and validate options
        String coordName = "";
        int port = -1;
        for (int i = 0; i < args.length; i++) switch (args[i]) {
            case "-n" -> {
                i++;
                if (i == args.length) throw new RuntimeException("Missing node count");
                else {
                    nodeCount = Integer.valueOf(args[i]);
                    maxByzantine = (int) (Math.ceil(nodeCount / 4) - 1);
                    phaseCount = maxByzantine + 1;
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
            case "-v" -> {
                verbose = true;
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
        if (nodeCount > 127 || nodeCount < 0) {
            System.out.println("Pleass provide a node count between 1 and 127");
            return;
        }
        
        
        
        // Open a listening socket for the group
        ServerSocket listener = new ServerSocket(port, nodeCount - 1, InetAddress.getLocalHost());
        
        
        // Connect to the coordinator
        int retries = 0;
        while (true) try {
            // This call is blocking, can also throw (e.g.: the server hasn't opened its listening socket yet)
            // ID is received only when all other peers do connect to the coordinator
            coordinator = new Socket(InetAddress.getByName(coordName), port);
            identifier = coordinator.getInputStream().read();
            break;
        }
        catch (IOException ex) {
            if (coordinator == null && retries < 3) {
                System.out.println("Failed connecting to coordinator, retrying in two seconds...");
                retries++;
                // Best known way to handle the containers' concurrent startup, for now... "depends_on" option isn't a sure-fire solution, but helps a lot
                Thread.sleep(2000);
            }
            else throw new RuntimeException("Failed connecting after " + retries + " retries. Quitting...", ex);
        }

        
        // Connect to the peers
        // If the node's ID is below the index, it listens, if it's above, it connects
        int nodeIndex = identifier - 1;
        peers = new Socket[nodeCount];
        for (int i = 0; i < peers.length; i++) {
            if (i < nodeIndex) {
                peers[i] = listener.accept();
            }
            else if (i > nodeIndex) {
                peers[i] = new Socket(InetAddress.getByName("phaseking_node_" + (i + 1) + ".phaseking_pk_net"), port);

            }
            else listener.close(); // Essentially, at this point the listener is done
        }


        // SYNC
        if (verbose) System.out.format("Fully connected. Standing by...\n", identifier);
        sync(210);
        

        // From now on, node will receive commands from coordinator
        // The value received will tell the behaviour for this node:
        // 1: random
        // 255: terminate
        // defautl: honest
        while (true) switch (coordinator.getInputStream().read()) {
            case 1 -> {
                if (verbose) System.out.println("I'm random");
                beRandom();
                coordinator.getOutputStream().write(2);
            }
            case 255 -> {
                for (int i = 0; i < peers.length; i++) {
                    if (i == identifier - 1) continue;
                    peers[i].close();
                }
                coordinator.close();
                return;
            }
            default -> {
                if (verbose) System.out.println("I'm honest");
                coordinator.getOutputStream().write(beHonest() ? 1 : 0);
            }
        }

    
    }


    /**
     * The honest node.
     *
     * On each phase: for round 1, the node shares its value, gathers those of
     * all others, and computes the majority; for round 2, if it is King then it
     * shares its majority, serving as the tiebreaker for others, and becoming
     * its new value; otherwise decides whether its new value is the majority or
     * the tiebreaker received from the king.
     *
     * @return
     * @throws IOException
     */
    static boolean beHonest() throws IOException {
        
        // Get an initial value
        boolean consensus = RNG.nextBoolean();

        for (int phase = 0; phase < phaseCount; phase++) {

            // Round 1
            // All nodes share their consensus
            boolean[] groupValues = new boolean[nodeCount];
            for (int i = 0; i < nodeCount; i++) {
                if (i == identifier - 1) continue;
                peers[i].getOutputStream().write(consensus ? 1 : 0);
                groupValues[i] = peers[i].getInputStream().read() == 1;
            }
            groupValues[identifier - 1] = consensus;

            // Compute the majority
            int trueCount = 0;
            int falseCount = 0;
            for (boolean groupValue : groupValues) if (groupValue) trueCount++; else falseCount++;
            boolean majority = trueCount > falseCount;
            int majCount = majority ? trueCount : falseCount;
            if (verbose) System.out.format("(PHASE %d - ROUND 1) Holding value: %b, majority: %b (%d occurrences)\n", phase + 1, consensus, majority, majCount);
            sync(210);


            // Round 2
            // If this node is king, send its majority to everybody else as the tiebreaker, otherwise listen and decide
            if (phase == identifier - 1) {
                if (verbose) System.out.format("(PHASE %d - ROUND 2) I am King, sending tiebreaker: %b\n", phase + 1, majority);
                for (int i = 0; i < nodeCount; i++) {
                    if (i == identifier - 1) continue;
                    peers[i].getOutputStream().write(majority ? 1 : 0);
                }
                consensus = majority; // majority and tiebreaker will be subjectively the same anyways
            }
            else {
                boolean tiebreaker = peers[phase].getInputStream().read() == 1;
                if (majCount > nodeCount / 2 + maxByzantine) {
                    if (verbose) System.out.format("(PHASE %d - ROUND 2) High consensus, choosing majority: %b\n", phase + 1, majority);
                    consensus = majority;
                }
                else {
                    if (verbose) System.out.format("(PHASE %d - ROUND 2) Consensus not high enough, choosing tiebreaker: %b\n", phase + 1, tiebreaker);
                    consensus = tiebreaker;
                }
            }
            sync(210);
        }
        
        return consensus;
    }
    
    /**
     * The random node.
     *
     * On each phase: for round 1, send a random value to each node and ignore
     * the received values; for round 2, if this node is king, send random
     * tiebreakers to each node, else ignore the received tiebreaker
     *
     * @throws IOException
     */
    static void beRandom() throws IOException {

        for (int phase = 0; phase < phaseCount; phase++) {
            
            // Round 1
            for (int i = 0; i < nodeCount; i++) {
                if (i == identifier - 1) continue;
                peers[i].getOutputStream().write(RNG.nextInt(2));
                peers[i].getInputStream().read();
            }
            if (verbose) System.out.format("(PHASE %d - ROUND 1) Randomizing\n", phase + 1);
            sync(210);

            // Round 2
            if (phase == identifier - 1) {
                if (verbose) System.out.format("(PHASE %d - ROUND 2) I am King, bombs away\n", phase + 1);
                for (int i = 0; i < nodeCount; i++) {
                    if (i == identifier - 1) continue;
                    peers[i].getOutputStream().write(RNG.nextInt(2));
                }
            }
            else {
                peers[phase].getInputStream().read();
            }
            sync(210);
        }
    }

}
