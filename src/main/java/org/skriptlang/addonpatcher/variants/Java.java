package org.skriptlang.addonpatcher.variants;

import org.skriptlang.addonpatcher.patcher.Patcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.jar.JarFile;

public class Java {

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("Usage: java -jar SkriptAddonPatcher.jar <addon jar>");
            System.exit(-1);
        }

        File file = new File(args[0]);
        if (!file.exists()) {
            System.err.println("The file " + args[0] + " does not exist");
            System.exit(-1);
        }

        // Patch a whole directory
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files == null)
                throw new IOException();

            boolean anyChanged = false;
            for (File loopFile : files) {
                if (!loopFile.getName().endsWith(".jar"))
                    continue; // skip non-.jar files

                boolean changed = patchFile(loopFile);
                if (changed) {
                    anyChanged = true;

                    System.out.println(loopFile + " was patched");
                }
            }

            if (!anyChanged) {
                System.out.println("No addons required patching");
            }

            return;
        }

        if (!file.getName().endsWith(".jar")) {
            System.err.println("That file isn't a jar file");
            System.exit(-1);
        }

        boolean changed = patchFile(file);

        if (changed) {
            System.out.println(file + " was patched");
        } else {
            System.out.println(file + " didn't require patching");
        }
    }

    /**
     * Patch a single file, return whether it was changed
     */
    private static boolean patchFile(File file) throws IOException {
        JarFile jarFile = new JarFile(file);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        boolean changed = Patcher.patchJar(jarFile, baos, true);

        if (changed) {
            // Replace the original jar file
            FileOutputStream fileOutputStream = new FileOutputStream(file);
            baos.writeTo(fileOutputStream);
            fileOutputStream.close();
            return true;
        }
        return false;
    }

}
