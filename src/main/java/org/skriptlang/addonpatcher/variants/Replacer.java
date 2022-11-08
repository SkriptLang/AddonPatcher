package org.skriptlang.addonpatcher.variants;

import org.skriptlang.addonpatcher.patcher.Patcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.jar.JarFile;
import java.util.logging.Logger;

public class Replacer {

    /**
     * Called very early in the plugin loading process,
     * not giving any addons time to load their code.
     */
    public static void replaceFiles(Logger logger) {
        File[] files = new File("plugins").listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".jar")) {

                    // Loops through all .jar files in plugins folder
                    try {
                        JarFile jarFile = new JarFile(file);
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();

                        // Patches the plugin jar
                        boolean changed = Patcher.patchJar(jarFile, baos, false);
                        if (changed) {
                            // If changes were made, replace the original plugin jar with the new variant.
                            FileOutputStream fileOutputStream = new FileOutputStream(file);
                            baos.writeTo(fileOutputStream);
                            fileOutputStream.close();

                            logger.warning(file.getName() + " is incompatible with newer Skript versions and was patched.");
                            logger.warning("Please report this to the author of this addon, " +
                                    "so they can make sure their addon works on newer Skript versions.");
                        }

                        jarFile.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                }
            }
        }
    }

}
