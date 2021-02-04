
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


/**
 * Phase King implementation: coordinator
 *
 * @author Project2100
 */
public class Coordinator {

    static {
        // This thread should be "main"
        Thread.currentThread().setUncaughtExceptionHandler((thread, exception) -> {
            Logger.getLogger(Coordinator.class.getName()).log(Level.SEVERE, "Uncaught exception in thread: " + thread.getName(), exception);
            System.exit(1);
        });
    }

    private static final ThreadLocalRandom RNG = ThreadLocalRandom.current();

    /*
    The pattern used to extract the node ID from a hostname
     */
    private static final Pattern NODE_HOSTNAME_ID = Pattern.compile("node_(\\d+)\\.");

    /**
     * Number of nodes
     */
    private static int nodeCount;

    /**
     * How many nodes can go awry before the protocol fails
     */
    private static int maxByzantine;

    /**
     * Phase count is determined by node count, which is known
     */
    private static int phaseCount;

    private static Socket[] nodes;
    
    private static Socket monitor;


    /**
     * Synchronization routine, with the help of the coordinator
     *
     * @throws IOException
     */
    private static void sync(int message, String log) throws IOException {
        for (Socket peer : nodes) {
            peer.getInputStream().read();
        }
        if (!log.isEmpty()) {
            System.out.println(log);
        }
        for (Socket peer : nodes) {
            peer.getOutputStream().write(message);
        }
    }


    public static void main(String[] args) throws IOException {
        
        // Read and validate options
        int numSessions = 1;
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
            case "-s" -> {
                i++;
                if (i == args.length) throw new RuntimeException("Missing session count");
                else numSessions = Integer.valueOf(args[i]);
            }
            case "-p" -> {
                i++;
                if (i == args.length) throw new RuntimeException("Missing TCP port");
                else port = Integer.valueOf(args[i]);
            }
            default -> throw new RuntimeException("Unrecognized option: " + args[i]);
        }
        if (numSessions <= 1) {
            System.out.println("Please provide a positive session count");
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


        
        // Open a listening socket, and connect to all the nodes
        nodes = new Socket[nodeCount];
        try (ServerSocket listener = new ServerSocket(port, nodeCount, InetAddress.getLocalHost())) {
            for (int i = 0; i < nodeCount; i++) {
                // Add the connection to the list, assign a unique identifier for the node to use
                // Blocking call
                Socket sock = listener.accept();
                System.out.println("Matching " + sock.getInetAddress().getHostName());
                Matcher m = NODE_HOSTNAME_ID.matcher(sock.getInetAddress().getHostName());
                m.find();
                int id = Integer.valueOf(m.group(1));
                nodes[id - 1] = sock;
            }

            // This should be useless once I figure out how to get a hostname from inside the node
            for (int i = 0; i < nodeCount; i++) {
                // Send ID to each node, also acts as a barrier point
                nodes[i].getOutputStream().write(i + 1);
            }
        }


        // SYNC
        sync(210, "");


        // Begin consensus sessions
        for (int ses = 0; ses < numSessions; ses++) {
            System.out.println("Starting session " + (ses + 1));


            // Pick some nodes that will act randomly
            List<Integer> a = IntStream.range(0, nodeCount).mapToObj(Integer::valueOf).collect(Collectors.toList());
            Collections.shuffle(a, RNG);
            boolean ishonest[] = new boolean[nodeCount];
            Arrays.fill(ishonest, true);
            a.subList(0, maxByzantine).forEach(idx -> ishonest[idx] = false);


            // Send the roles to each node
            for (int i = 0; i < nodes.length; i++) {
                //nodes[i].getOutputStream().write(i <= maxbyzantines ? (i == honestPKNode ? 0 : 1) : 0);
                nodes[i].getOutputStream().write(ishonest[i] ? 0 : 1);
            }


            // Synchronize the nodes for prettier output when all containers are attached to the same console
            for (int phase = 0; phase < phaseCount; phase++) {
                sync(210, (phase + 1) + ":1 over");
                sync(210, (phase + 1) + ":2 over");
            }


            // Receive the nodes' consensus estimate, and display the results
            int trueCount = 0, falseCount = 0;
            for (Socket node : nodes) {
                int estimate = node.getInputStream().read();
                if (estimate == 0) falseCount++;
                else if (estimate == 1) trueCount++;
            }
            if ((trueCount > 0 && falseCount == 0) || (trueCount == 0 && falseCount > 0)) {
                System.out.format("Reached consensus (T: %d, F: %d)\n", trueCount, falseCount);
            }
            else {
                System.out.format("Could not establish consensus (T: %d, F: %d)\n", trueCount, falseCount);
            }
        }

        
        // Session is finished, close all sockets
        System.out.println("Sending termination signal");
        for (Socket node : nodes) {
            node.getOutputStream().write(255);
            node.close();
        }

    }
}
