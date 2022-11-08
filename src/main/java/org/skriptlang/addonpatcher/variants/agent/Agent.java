package org.skriptlang.addonpatcher.variants.agent;

import com.sun.tools.attach.VirtualMachine;
import org.skriptlang.addonpatcher.patcher.Patcher;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * The Agent variant attaches an agent to the server using the Attach API,
 * and then adds a class file transformer using the Instrumentation API.
 */
public class Agent {

    public static void agentmain(String args, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassPatcher());
    }

    public static void main(String[] args) throws Exception {
        // First, an early check if the user's running a JDK
        try {
            Class.forName("com.sun.tools.attach.VirtualMachine");
        } catch (ClassNotFoundException e) {
            // Try activating tools.jar
            // Check if Java 8, since tools.jar was deprecated after that
            boolean isJava8 = "1.8".equals(System.getProperty("java.vm.specification.version"));

            try {
                if (isJava8) {
                    enableTools();
                    Class.forName("com.sun.tools.attach.VirtualMachine");
                } else {
                    throw new RuntimeException(); // ugly code to not run enableTools, but still print the errors
                }
            } catch (Exception e2) {
                System.err.println();
                System.err.println("Couldn't find Attach API: " + e);
                System.err.println("Make sure you're using a JDK installation of Java, and not a JRE");
                System.err.println("If you're using a JRE, either install and run your server using a JDK, " +
                        "or use the Replacer variant");
                System.err.println();
                System.err.println("Version: " + System.getProperty("java.version") + " (" + System.getProperty("java.vm.specification.version") + ")");
                System.err.println("Java home: " + System.getProperty("java.home"));
                System.err.println(" ");

                e.printStackTrace();
                if (isJava8) { // only print second stack trace if enableTools was called
                    e2.printStackTrace();
                    System.exit(70);
                }
                System.exit(69);
            }
        }

        String path = args[0];
        String pid = args[1];

        VirtualMachine.attach(pid).loadAgent(path);
    }

    /**
     * Adds the tools.jar file from a JDK to the class path, only for Java 8.
     */
    public static void enableTools() throws Exception {
        ClassLoader classLoader = Agent.class.getClassLoader();

        URLClassLoader urlClassLoader = (URLClassLoader) classLoader;

        String javaHome = System.getProperty("java.home");
        if (javaHome.endsWith(File.separator + "jre"))
            javaHome = javaHome.substring(0, javaHome.length() - (File.separator + "jre").length());

        File toolsJar = new File(javaHome + File.separator + "lib" + File.separator + "tools.jar");
        if (!toolsJar.exists())
            throw new IllegalStateException("The tools.jar doesn't exist, checked " + toolsJar.getAbsolutePath());
        URL url = toolsJar.toURI().toURL();

        Method method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
        method.setAccessible(true);
        method.invoke(urlClassLoader, url);
    }


    public static class ClassPatcher implements ClassFileTransformer {

        private final Logger logger = Logger.getLogger("AddonPatcher");
        private final List<URL> reportedURLs = new ArrayList<>();

        @Override
        public byte[] transform(ClassLoader loader,
                                String className,
                                Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain,
                                byte[] classfileBuffer) {
            try {
                return transformUnsafe(loader, classfileBuffer);
            } catch (Exception e) {
                logger.warning("Caught exception while transforming " + className + ", please report this.");
                e.printStackTrace();
                return null;
            }
        }

        public byte[] transformUnsafe(ClassLoader loader, byte[] classfileBuffer) {
            AtomicBoolean used = new AtomicBoolean();
            byte[] bytes = Patcher.patchClass(classfileBuffer, used);
            if (used.get()) {
                // Get the URL where this class originated from
                if (loader instanceof URLClassLoader) {
                    URL[] urls = ((URLClassLoader) loader).getURLs();
                    if (urls.length == 1) {
                        URL url = urls[0];
                        if (!reportedURLs.contains(url)) {
                            reportedURLs.add(url);

                            File file = new File(url.getFile());

                            logger.warning(file.getName() + " is incompatible with newer Skript versions (without AddonPatcher).");
                            logger.warning("Please report this to the author of this addon, " +
                                    "so they can make sure their addon works on newer Skript versions.");
                        }
                    }
                }

                return bytes;
            }

            return null; // no transformation needed
        }
    }

}
