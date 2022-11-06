package org.skriptlang.addonpatcher.patcher;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.skriptlang.addonpatcher.Util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

public class Patcher {

    /**
     * Patches the given {@link JarFile}, but writes the output jar
     * to the given {@link OutputStream}, which is closed afterwards.
     */
    public static boolean patchJar(JarFile jarFile, OutputStream outputStream, boolean printExceptions) throws IOException {
        JarOutputStream jarOutputStream = new JarOutputStream(outputStream);

        Enumeration<JarEntry> enumeration = jarFile.entries();
        boolean changed = false;
        while (enumeration.hasMoreElements()) {
            JarEntry jarEntry = enumeration.nextElement();
            try {
                changed |= patchJarEntry(jarFile, jarOutputStream, jarEntry);
            } catch (Exception e) {
                if (printExceptions)
                    e.printStackTrace();
            }
        }
        jarOutputStream.close();

        return changed;
    }

    /**
     * Patch a single jar entry
     */
    public static boolean patchJarEntry(JarFile jarFile, JarOutputStream jarOutputStream, JarEntry jarEntry) throws IOException {
        JarEntry newJarEntry = Util.newJarEntry(jarEntry);

        InputStream inputStream = jarFile.getInputStream(jarEntry);
        if (!newJarEntry.getName().endsWith(".class")) {
            jarOutputStream.putNextEntry(newJarEntry);
            Util.transferStreams(inputStream, jarOutputStream);
            return false;
        }

        byte[] oldClassBytes = Util.readAll(inputStream);

        AtomicBoolean used = new AtomicBoolean();
        byte[] newBytes = patchClass(oldClassBytes, used);

        if (!used.get()) { // Class didn't have anything replaced
            jarOutputStream.putNextEntry(newJarEntry);
            jarOutputStream.write(oldClassBytes);
            return false;
        } else { // The entry needs replacing
            newJarEntry.setComment("Converted with SkriptAddonPatcher");
            newJarEntry.setLastModifiedTime(FileTime.from(Instant.now()));
            newJarEntry.setSize(newBytes.length);

            jarOutputStream.putNextEntry(newJarEntry);
            jarOutputStream.write(newBytes);
            return true;
        }
    }

    /**
     * Patches the class, given as a byte array.
     * The {@link AtomicBoolean} will be set to true when a modification is made.
     */
    public static byte[] patchClass(byte[] classBytes, AtomicBoolean used) {
        ClassReader classReader = new ClassReader(classBytes);
        ClassWriter classWriter = new ClassWriter(0);

        /*
        Parallel script loading (https://github.com/SkriptLang/Skript/pull/3924)
         */
        ClassVisitor currentScriptReplacer = new MethodWrappingVisitor(classWriter, mv -> new FieldEncapsulatingMethodVisitor(
                mv,
                "ch/njol/skript/ScriptLoader",
                "currentScript",
                "Lch/njol/skript/config/Config;",
                "getCurrentScript",
                "setCurrentScript",
                used
        ));

        ClassVisitor currentSectionsReplacer = new MethodWrappingVisitor(currentScriptReplacer, mv -> new FieldEncapsulatingMethodVisitor(
                mv,
                "ch/njol/skript/ScriptLoader",
                "currentSections",
                "Ljava/util/List;",
                "getCurrentSections",
                "setCurrentSections",
                used
        ));

        ClassVisitor currentLoopsReplacer = new MethodWrappingVisitor(currentSectionsReplacer, mv -> new FieldEncapsulatingMethodVisitor(
                mv,
                "ch/njol/skript/ScriptLoader",
                "currentLoops",
                "Ljava/util/List;",
                "getCurrentLoops",
                "setCurrentLoops",
                used
        ));

        ClassVisitor hasDelayBeforeReplacer = new MethodWrappingVisitor(currentLoopsReplacer, mv -> new FieldEncapsulatingMethodVisitor(
                mv,
                "ch/njol/skript/ScriptLoader",
                "hasDelayBefore",
                "Lch/njol/util/Kleenean;",
                "getHasDelayBefore",
                "setHasDelayBefore",
                used
        ));

        /*
        Structure API (https://github.com/SkriptLang/Skript/pull/4108)
        */
        ClassVisitor getCurrentScriptReplacer = new MethodWrappingVisitor(hasDelayBeforeReplacer, mv -> new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                String parserInstance = "ch/njol/skript/lang/parser/ParserInstance";
                String script = "org/skriptlang/skript/lang/script/Script";
                String config = "ch/njol/skript/config/Config";
                String trigger = "ch/njol/skript/lang/Trigger";
                String file = "java/io/File";

                if (owner.equals(parserInstance) && name.equals("getCurrentScript") && descriptor.equals("()L"+config+";")) {
                    // Replace ParserInstance#getCurrentScript->Config with ParserInstance#getCurrentScript->Script#getConfig->Config
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, parserInstance, "getCurrentScript", "()L"+script+";", false);
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, script, "getConfig", "()L"+config+";", false);
                    used.set(true);

                    return;
                }

                if (owner.equals(trigger) && name.equals("getScript") && descriptor.equals("()L"+file+";")) {
                    // Replace Trigger#getScript->File with Trigger#getScript->Script#getConfig->Config#getFile->File
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, trigger, "getScript", "()L"+script+";", false);
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, script, "getConfig", "()L"+config+";", false);
                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, config, "getFile", "()L"+file+";", false);
                    used.set(true);

                    return;
                }

                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
        });

        classReader.accept(getCurrentScriptReplacer, 0);

        return classWriter.toByteArray();
    }

}
