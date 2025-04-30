package one.wabbit.inspect;

import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.AgentInitializationException; // Added
import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine; // Attach API VM
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream; // Added
import java.io.BufferedReader; // Added
import java.io.InputStreamReader; // Added
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Properties; // Added
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets; // Added

/**
 * Helper program launched by Kotlin code to interact with a target JVM.
 * Supports multiple commands:
 * - list-agents: Lists agent properties.
 * - load-agent: Loads a native agent dynamically.
 * - load-jdwp: Enables the JDWP debugging agent dynamically.
 * - get-locals: Enables JDWP (if needed) and retrieves locals via JDI.
 * - --connect: Connects directly to an existing JDWP port to get locals.
 * <p>
 * Prints results/status to standard output.
 * Exits with 0 on success, non-zero on failure.
 */
public class InspectorGadget {

    private static final String JDI_SOCKET_ATTACH_CONNECTOR = "com.sun.jdi.SocketAttach";
    private static final String DIAGNOSTIC_COMMAND_START_DEBUGGING = "start_java_debugging";
    private static final String HOTSPOT_VM_CLASS_NAME = "sun.tools.attach.HotSpotVirtualMachine"; // Internal API

    public static void main(String[] args) {
        if (args.length < 1) { // Need at least a command or --connect
            printUsage();
            System.exit(1);
        }

        String command = args[0];

        // Handle direct connect separately as it doesn't need PID first
        if ("--connect".equals(command)) {
            if (args.length != 3) {
                System.err.println("Usage: InspectorGadget --connect <host>:<port> <threadName>");
                System.exit(1);
            }
            String hostPort = args[1];
            String connectThreadName = args[2];
            handleDirectConnectLocals(hostPort, connectThreadName); // Exits internally
            return; // Should not be reached if handler works
        }

        // Other commands require PID as the second argument
        if (args.length < 2) {
            printUsage();
            System.exit(1);
        }
        String pid = args[1];

        try {
            switch (command) {
                case "list-agents":
                    if (args.length != 2) printUsage();
                    handleListAgents(pid);
                    break;
                case "load-agent": // Args: load-agent <pid> <agent-path> [options]
                    if (args.length < 3) printUsage();
                    String agentPath = args[2];
                    String agentOptions = (args.length > 3) ? args[3] : null;
                    handleLoadAgent(pid, agentPath, agentOptions);
                    break;
                case "load-jdwp": // Args: load-jdwp <pid> [port]
                    String port = (args.length > 2) ? args[2] : findFreePort();
                    handleLoadJdwp(pid, port);
                    break;
                case "get-locals": // Args: get-locals <pid> <threadName> [port_for_dynamic_load]
                    if (args.length < 3) printUsage();
                    String threadName = args[2];
                    String dynamicPort = (args.length > 3) ? args[3] : findFreePort();
                    handleGetLocalsDynamic(pid, threadName, dynamicPort);
                    break;
                default:
                    System.err.println("Unknown command: " + command);
                    printUsage();
                    System.exit(1);
            }
            System.exit(0); // Success if command handler didn't exit with error
        } catch (Exception e) {
            System.err.println("Error executing command '" + command + "' on PID " + pid + ": " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(10); // General error exit code
        }
    }

    static void printUsage() {
        System.err.println("Usage: InspectorGadget <command> <pid | options...> [args...]");
        System.err.println("Commands:");
        System.err.println("  list-agents <pid>                    - Lists agent properties loaded in the target VM.");
        System.err.println("  load-agent <pid> <agent-path> [opts] - Loads a native agent library dynamically.");
        System.err.println("  load-jdwp <pid> [port]               - Dynamically enables JDWP agent. Prints assigned port.");
        System.err.println("  get-locals <pid> <threadName> [port] - Enables JDWP (using [port] or random) and gets locals.");
        System.err.println("  --connect <host>:<port> <threadName> - Connects to existing JDWP and gets locals.");
    }

    // --- Command Handlers ---

    private static void handleListAgents(String pid) throws AttachNotSupportedException, IOException {
        System.out.println("InspectorGadget: Listing agent properties for PID " + pid + "...");
        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(pid);
            Properties agentProps = vm.getAgentProperties();
            if (agentProps.isEmpty()) {
                System.out.println("No agent properties found (or agent did not set any).");
            } else {
                System.out.println("--- Agent Properties START ---");
                // agentProps.store(System.out, null); // Simple way to print
                agentProps.forEach((key, value) -> System.out.println(key + "=" + value));
                System.out.println("--- Agent Properties END ---");
            }
        } finally {
            if (vm != null) vm.detach();
            System.out.println("InspectorGadget: Detached.");
        }
    }

    private static void handleLoadAgent(String pid, String agentPath, String options)
            throws AttachNotSupportedException, IOException, AgentLoadException, AgentInitializationException {
        System.out.println("InspectorGadget: Loading agent '" + agentPath + "' for PID " + pid + " with options: " + options);
        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(pid);
            // Use loadAgentPath for absolute paths
            vm.loadAgentPath(agentPath, options);
            System.out.println("Agent loaded successfully.");
        } finally {
            if (vm != null) vm.detach();
            System.out.println("InspectorGadget: Detached.");
        }
    }

    private static void handleLoadJdwp(String pid, String port) throws AttachNotSupportedException, IOException {
        System.out.println("InspectorGadget: Enabling JDWP on PID " + pid + " (requesting port " + port + ")...");
        VirtualMachine vm = null;
        boolean success = false;
        try {
            vm = VirtualMachine.attach(pid);
            success = enableDebugging(vm, port);
            if (success) {
                System.out.println("JDWP agent enabled/requested successfully. Listening on/using port: " + port);
                // Output port clearly for potential reuse
                System.out.println("JDWP_PORT=" + port);
            } else {
                System.err.println("Failed to enable JDWP agent.");
                System.exit(11); // Specific exit code for JDWP load failure
            }
        } finally {
            if (vm != null) vm.detach();
            System.out.println("InspectorGadget: Detached.");
        }
    }

    private static void handleGetLocalsDynamic(String pid, String threadName, String dynamicPort) throws Exception {
        System.out.println("InspectorGadget: Getting locals via dynamic attach for PID " + pid + ", Thread: " + threadName + ", Port: " + dynamicPort);
        VirtualMachine attachVm = null;
        com.sun.jdi.VirtualMachine jdiVm = null;

        try {
            // 1. Attach
            System.out.println("InspectorGadget: Attaching to PID " + pid + "...");
            attachVm = VirtualMachine.attach(pid);
            System.out.println("InspectorGadget: Attached successfully via Attach API.");

            // 2. Enable Debugging
            boolean debuggingEnabled = enableDebugging(attachVm, dynamicPort);
            if (!debuggingEnabled) {
                System.err.println("InspectorGadget: Failed to enable debugging on target VM.");
                System.exit(2); // Exit code from original code
            }
            System.out.println("InspectorGadget: Debugging enabled/requested on target VM using port " + dynamicPort);

            // 3. Detach
            System.out.println("InspectorGadget: Detaching from Attach API handle...");
            attachVm.detach();
            attachVm = null;
            System.out.println("InspectorGadget: Detached.");

            Thread.sleep(500); // Allow agent socket to start

            // 4. Connect JDI
            System.out.println("InspectorGadget: Connecting via JDI to localhost:" + dynamicPort + "...");
            jdiVm = connectViaJdi("localhost", dynamicPort);
            System.out.println("InspectorGadget: Connected successfully via JDI.");

            // 5. Get Locals
            printTargetThreadLocals(jdiVm, threadName);
            System.out.println("InspectorGadget: Local inspection complete.");

        } finally {
            // Cleanup JDI and Attach VM
            if (jdiVm != null) try { jdiVm.dispose(); System.out.println("InspectorGadget: JDI VM disposed."); } catch (Exception e) { /* ignore */ }
            if (attachVm != null) try { attachVm.detach(); System.out.println("InspectorGadget: Attach API handle detached in finally block."); } catch (Exception e) { /* ignore */ }
        }
        // Success exit is handled by main caller
    }

    private static void handleDirectConnectLocals(String hostPort, String threadName) {
        System.out.println("InspectorGadget: Getting locals via direct connect to " + hostPort + ", Thread: " + threadName);
        com.sun.jdi.VirtualMachine jdiVm = null;
        String targetHost;
        String targetPort;

        try {
            // Parse host:port
            int colonIndex = hostPort.lastIndexOf(':');
            if (colonIndex == -1 || colonIndex == 0 || colonIndex == hostPort.length() - 1) {
                System.err.println("Invalid host:port format: " + hostPort);
                System.exit(1);
            }
            targetHost = hostPort.substring(0, colonIndex);
            targetPort = hostPort.substring(colonIndex + 1);

            // 1. Connect JDI
            System.out.println("InspectorGadget: Connecting via JDI to " + targetHost + ":" + targetPort + "...");
            jdiVm = connectViaJdi(targetHost, targetPort);
            System.out.println("InspectorGadget: Connected successfully via JDI.");

            // 2. Get Locals
            printTargetThreadLocals(jdiVm, threadName);
            System.out.println("InspectorGadget: Local inspection complete.");

            System.exit(0); // Explicit success exit for this handler

        } catch (IOException | IllegalConnectorArgumentsException e) {
            System.err.println("InspectorGadget: Failed to connect via JDI: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(12); // Specific exit code for JDI connect failure
        } catch (Exception e) {
            System.err.println("InspectorGadget: Error during direct connect or inspection: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(13); // Specific exit code for other direct connect errors
        } finally {
            if (jdiVm != null) try { jdiVm.dispose(); System.out.println("InspectorGadget: JDI VM disposed."); } catch (Exception e) { /* ignore */ }
        }
    }


    // --- Helper methods (enableDebugging, tryDiagnosticCommand, tryLoadAgentPath, connectViaJdi, printTargetThreadLocals, valueToString, findFreePort, guessLibjdwpPath) ---
    // [These remain largely the same as the previous version, ensure they are included here]

    /**
     * Tries to enable debugging, preferring diagnostic command, falling back to loadAgentPath.
     */
    private static boolean enableDebugging(VirtualMachine attachVm, String port) {
        // Try diagnostic command first (more robust if available)
        if (tryDiagnosticCommand(attachVm, port)) {
            return true;
        }
        // Fallback to loadAgentPath
        return tryLoadAgentPath(attachVm, port);
    }

    /**
     * Attempts to enable debugging using the 'start_java_debugging' diagnostic command via reflection.
     */
    private static boolean tryDiagnosticCommand(VirtualMachine attachVm, String port) {
        try {
            Class<?> vmClass = attachVm.getClass();
            Class<?> hotspotClass = null;
            try {
                hotspotClass = Class.forName(HOTSPOT_VM_CLASS_NAME, true, vmClass.getClassLoader());
            } catch (ClassNotFoundException e) {
                System.out.println("InspectorGadget: HotSpotVirtualMachine class not found, cannot use diagnostic command.");
                return false;
            }

            if (!hotspotClass.isInstance(attachVm)) {
                System.out.println("InspectorGadget: Target VM is not an instance of HotSpotVirtualMachine (" + attachVm.getClass().getName() + "), cannot use diagnostic command.");
                return false;
            }

            Method executeCmdMethod = hotspotClass.getMethod("executeCommand", String.class, Object[].class);
            String agentArgs = String.format("transport=dt_socket,server=y,suspend=n,address=%s", port);

            System.out.println("InspectorGadget: Attempting diagnostic command '" + DIAGNOSTIC_COMMAND_START_DEBUGGING + "' with args: " + agentArgs);
            executeCmdMethod.invoke(attachVm, DIAGNOSTIC_COMMAND_START_DEBUGGING, new Object[]{agentArgs});
            System.out.println("InspectorGadget: Diagnostic command executed successfully (or without throwing).");
            return true;

        } catch (NoSuchMethodException | SecurityException e) {
            System.out.println("InspectorGadget: Could not find or access executeCommand method via reflection. Falling back. Error: " + e);
            return false;
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            System.err.println("InspectorGadget: Error executing diagnostic command: " + e.getMessage());
            // Print cause if available, as InvocationTargetException wraps the real error
            Throwable cause = e.getCause();
            if (cause != null) {
                System.err.println("InspectorGadget: Cause: " + cause.getMessage());
                cause.printStackTrace(System.err);
            } else {
                e.printStackTrace(System.err);
            }
            return false;
        } catch (Exception e) { // Catch unexpected errors during reflection/invocation
            System.err.println("InspectorGadget: Unexpected error trying diagnostic command: " + e.getMessage());
            e.printStackTrace(System.err);
            return false;
        }
    }

    /**
     * Attempts to enable debugging by loading the JDWP agent library directly.
     */
    private static boolean tryLoadAgentPath(VirtualMachine attachVm, String port) {
        try {
            String jdwpLibPath = guessLibjdwpPath();
            if (jdwpLibPath == null || !new File(jdwpLibPath).exists()) {
                System.err.println("InspectorGadget: Could not find JDWP agent library (tried path: " + jdwpLibPath + "). Cannot use loadAgentPath.");
                return false;
            }

            String agentArgs = String.format("transport=dt_socket,server=y,suspend=n,address=%s", port);
            System.out.println("InspectorGadget: Attempting loadAgentPath for '" + jdwpLibPath + "' with args: " + agentArgs);
            attachVm.loadAgentPath(jdwpLibPath, agentArgs);
            System.out.println("InspectorGadget: loadAgentPath executed successfully.");
            return true;
        } catch (AgentLoadException e) {
            System.err.println("InspectorGadget: Failed to load JDWP agent via loadAgentPath: " + e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("Agent_OnAttach")) {
                System.err.println("InspectorGadget: Hint: This often means the JDWP library found doesn't support dynamic attachment, or the path is wrong.");
            }
            e.printStackTrace(System.err);
            return false;
        } catch (IOException e) {
            System.err.println("InspectorGadget: I/O error during loadAgentPath: " + e.getMessage());
            e.printStackTrace(System.err);
            return false;
        } catch (AgentInitializationException e) { // Catch init errors too
            System.err.println("InspectorGadget: Agent initialization failed via loadAgentPath: " + e.getMessage() + " (Return code: " + e.returnValue() + ")");
            e.printStackTrace(System.err);
            return false;
        } catch (Exception e) { // Catch unexpected errors
            System.err.println("InspectorGadget: Unexpected error trying loadAgentPath: " + e.getMessage());
            e.printStackTrace(System.err);
            return false;
        }
    }

    /**
     * Connects to the target JVM via JDI using the SocketAttach connector.
     */
    private static com.sun.jdi.VirtualMachine connectViaJdi(String host, String port) throws IOException, IllegalConnectorArgumentsException {
        AttachingConnector connector = Bootstrap.virtualMachineManager().allConnectors()
                .stream()
                .filter(c -> JDI_SOCKET_ATTACH_CONNECTOR.equals(c.name()))
                .map(c -> (AttachingConnector) c)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Could not find JDI connector: " + JDI_SOCKET_ATTACH_CONNECTOR));

        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("hostname").setValue(host);
        arguments.get("port").setValue(port);
        if (arguments.containsKey("timeout")) {
            arguments.get("timeout").setValue("10000"); // 10 seconds
        }

        return connector.attach(arguments);
    }

    /**
     * Finds the specified thread, suspends it, prints its top frame's locals, and resumes it.
     */
    private static void printTargetThreadLocals(com.sun.jdi.VirtualMachine jdiVm, String targetThreadName) {
        ThreadReference targetThread = null;
        try {
            targetThread = jdiVm.allThreads()
                    .stream()
                    .filter(t -> targetThreadName.equals(t.name())) // Exact name match
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Thread with name '" + targetThreadName + "' not found in target VM."));

            System.out.println("InspectorGadget: Found target thread: " + targetThread.name() + " (ID: " + targetThread.uniqueID() + ")");

            System.out.println("InspectorGadget: Suspending thread...");
            targetThread.suspend();
            System.out.println("InspectorGadget: Thread suspended.");

            if (targetThread.frameCount() == 0) {
                System.out.println("InspectorGadget: Target thread has no stack frames.");
                return;
            }

            StackFrame topFrame = targetThread.frame(0);
            System.out.println("InspectorGadget: Inspecting top frame: " + topFrame.location());

            List<LocalVariable> visibleVars = topFrame.visibleVariables();
            if (visibleVars.isEmpty()) {
                System.out.println("InspectorGadget: No visible local variables in the top frame.");
            } else {
                System.out.println("--- LOCALS START ---"); // Marker for parsing
                for (LocalVariable var : visibleVars) {
                    try {
                        Value value = topFrame.getValue(var);
                        String valueStr = valueToString(value);
                        System.out.println(var.name() + " = " + valueStr);
                    } catch (InvalidStackFrameException e) {
                        System.err.println("InspectorGadget: Stack frame became invalid while reading variable '" + var.name() + "'");
                        break;
                    } catch (Exception e) {
                        System.err.println("InspectorGadget: Error getting value for variable '" + var.name() + "': " + e.getMessage());
                        System.out.println(var.name() + " = <Error retrieving value>");
                    }
                }
                System.out.println("--- LOCALS END ---"); // Marker for parsing
            }

        } catch (IncompatibleThreadStateException e) {
            System.err.println("InspectorGadget: Thread was not suspended or in incompatible state: " + e.getMessage());
            e.printStackTrace(System.err);
        } catch (AbsentInformationException e) {
            System.err.println("InspectorGadget: Debug information (variable names) not available. Compile target code with debug info (-g).");
        } catch (Exception e) {
            System.err.println("InspectorGadget: Error inspecting thread locals: " + e.getMessage());
            e.printStackTrace(System.err);
        } finally {
            if (targetThread != null && targetThread.isSuspended()) {
                try {
                    System.out.println("InspectorGadget: Resuming thread...");
                    targetThread.resume();
                    System.out.println("InspectorGadget: Thread resumed.");
                } catch (Exception e) {
                    System.err.println("InspectorGadget: Failed to resume thread: " + e.getMessage());
                }
            }
        }
    }

    /** Basic conversion of JDI Value to a String representation. */
    private static String valueToString(Value value) {
        if (value == null) return "null";
        if (value instanceof StringReference) {
            String s = ((StringReference) value).value();
            if (s.length() > 100) s = s.substring(0, 97) + "...";
            s = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
            return "\"" + s + "\"";
        }
        if (value instanceof PrimitiveValue) return value.toString();
        if (value instanceof ObjectReference) {
            ObjectReference objRef = (ObjectReference) value;
            ReferenceType type = objRef.referenceType();
            if (type instanceof ArrayType) return type.name() + "[length=" + ((ArrayReference)objRef).length() + "]@" + objRef.uniqueID();
            if (type != null) return type.name() + "@" + objRef.uniqueID();
            return "Object@"+ objRef.uniqueID();
        }
        return value.toString(); // Fallback
    }

    /** Finds an available TCP port on localhost. Basic. */
    private static String findFreePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            socket.setReuseAddress(true);
            return String.valueOf(socket.getLocalPort());
        } catch (IOException e) {
            int fallbackPort = 5000 + (int)(Math.random() * 1000);
            System.err.println("InspectorGadget: Warning - Could not bind to port 0, using random fallback " + fallbackPort);
            return String.valueOf(fallbackPort);
        }
    }

    /** Tries to find the JDWP native library path based on java.home. Heuristic. */
    private static String guessLibjdwpPath() {
        String javaHome = System.getProperty("java.home");
        String osName = System.getProperty("os.name", "").toLowerCase();
        String osArch = System.getProperty("os.arch", "").toLowerCase();
        String libFileName;
        String pathSeparator = File.separator;

        if (osName.contains("win")) libFileName = "jdwp.dll";
        else if (osName.contains("mac")) libFileName = "libjdwp.dylib";
        else libFileName = "libjdwp.so";

        String[] relativePaths = {
                "lib" + pathSeparator + libFileName,
                "jre" + pathSeparator + "lib" + pathSeparator + libFileName,
                "lib" + pathSeparator + osArch + pathSeparator + libFileName,
                ".." + pathSeparator + "lib" + pathSeparator + libFileName,
                "bin" + pathSeparator + libFileName, // Win
                "jre" + pathSeparator + "bin" + pathSeparator + libFileName // Win
        };

        for (String relativePath : relativePaths) {
            try {
                File candidate = new File(javaHome, relativePath).getCanonicalFile();
                if (candidate.exists() && candidate.isFile()) {
                    System.out.println("InspectorGadget: Found potential JDWP library at: " + candidate.getAbsolutePath());
                    return candidate.getAbsolutePath();
                }
            } catch (IOException e) { /* Ignore paths that cannot be canonicalized */ }
        }
        System.err.println("InspectorGadget: WARNING - Could not reliably find JDWP library path relative to java.home (" + javaHome + "). Returning default guess.");
        return new File(javaHome, "lib" + pathSeparator + libFileName).getAbsolutePath(); // Default guess
    }
}
