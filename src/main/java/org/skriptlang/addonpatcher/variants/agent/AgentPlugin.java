package org.skriptlang.addonpatcher.variants.agent;

import org.skriptlang.addonpatcher.Plugin;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.logging.Logger;

public class AgentPlugin {

    /**
     * Starts and attaches the agent to the server.
     */
    public static boolean load(Plugin plugin, File file) {
        Logger logger = plugin.getLogger();

        boolean success;
        try {
            success = startAgent(logger, file);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        if (success) {
            logger.info("Attached");
            return true;
        } else {
            return false;
        }
    }

    /**
     * Start & attach the agent, using a new process to avoid security manager interference.
     */
    public static boolean startAgent(Logger logger, File file) throws IOException {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        String pid = name.substring(0, name.indexOf('@'));
        logger.info("Attaching to PID " + pid);

        String jarPath = file.getCanonicalPath();

        // Start a new Java process (same JVM as server) with the Agent class as main

        String javaCommand = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";

        Process process = new ProcessBuilder(javaCommand, "-cp", jarPath, Agent.class.getName(), jarPath, pid)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .start();

        try {
            process.waitFor();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        int exitValue = process.exitValue();
        if (exitValue != 0) {
            logger.severe("Couldn't attach Agent, see errors above (error code " + exitValue + ")");
            return false;
        }
        return true;
    }

}
