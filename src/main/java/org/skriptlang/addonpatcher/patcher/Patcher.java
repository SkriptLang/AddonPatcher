package org.skriptlang.addonpatcher.patcher;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
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

import static org.objectweb.asm.Opcodes.*;

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
        // Trigger constructor adds extra stuff to the stack, so max stack size must be recomputed
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);

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
                    // Replace ParserInstance#getCurrentScript->Config with
                    // Optional.of(ParserInstance.get()).filter(ParserInstance::isActive).orElse(null)->Config

                    // 'imports'
                    String object = "java/lang/Object";
                    String string = "java/lang/String";
                    String primitiveBoolean = "Z";

                    String optional = "java/util/Optional";
                    String function = "java/util/function/Function";
                    String predicate = "java/util/function/Predicate";

                    String lambdaMetafactory = "java/lang/invoke/LambdaMetafactory";
                    String lookup = "java/lang/invoke/MethodHandles$Lookup";
                    String methodType = "java/lang/invoke/MethodType";
                    String methodHandle = "java/lang/invoke/MethodHandle";
                    String callSite = "java/lang/invoke/CallSite";

                    // Wrap ParserInstance in optional
                    super.visitMethodInsn(INVOKESTATIC, optional, "ofNullable", "(L"+object+";)L"+optional+";", false);

                    // Method reference ParseInstance::isActive
                    visitInvokeDynamicInsn(
                            "test", "()L" + predicate + ";",
                            // Handle for the meta factory (I got most of these by looking what
                            // ASMs ClassReader gave me for a regularly compiled class
                            new Handle(
                                    H_INVOKESTATIC,
                                    lambdaMetafactory,
                                    "metafactory",
                                    "(L"+lookup+";L"+string+";L"+methodType+";L"+methodType+";L"+methodHandle+";L"+methodType+";)L"+callSite+";",
                                    false
                            ),
                            // Descriptor of Predicate#test
                            Type.getMethodType("(L"+object+";)"+primitiveBoolean),
                            // Handle for ParserInstance#isActive
                            new Handle(
                                    H_INVOKEVIRTUAL,
                                    parserInstance,
                                    "isActive",
                                    "()"+primitiveBoolean,
                                    false
                            ),
                            // Descriptor of ParserInstance#isActive
                            Type.getMethodType("(L"+parserInstance+";)"+primitiveBoolean)
                    );
                    // Filter the Optional with ParserInstance#isActive
                    visitMethodInsn(INVOKEVIRTUAL, optional, "filter", "(L"+predicate+";)L"+optional+";", false);

                    // Method reference ParserInstance::getCurrentScript
                    visitInvokeDynamicInsn(
                            "apply", "()L" + function + ";",
                            // Handle for the meta factory (I got most of these by looking what
                            // ASMs ClassReader gave me for a regularly compiled class
                            new Handle(
                                    H_INVOKESTATIC,
                                    lambdaMetafactory,
                                    "metafactory",
                                    "(L"+lookup+";L"+string+";L"+methodType+";L"+methodType+";L"+methodHandle+";L"+methodType+";)L"+callSite+";",
                                    false
                            ),
                            // Descriptor of Function#apply
                            Type.getMethodType("(L"+object+";)L"+object+";"),
                            // Handle for ParserInstance#getCurrentScript
                            new Handle(
                                    H_INVOKEVIRTUAL,
                                    parserInstance,
                                    "getCurrentScript",
                                    "()L"+script+";",
                                    false
                            ),
                            // Descriptor of ParserInstance#getCurrentScript
                            Type.getMethodType("(L"+parserInstance+";)L"+script+";")
                    );
                    // Map the Optional with ParserInstance#getCurrentScript
                    visitMethodInsn(INVOKEVIRTUAL, optional, "map", "(L"+function+";)L"+optional+";", false);

                    // Method reference Script::getConfig
                    visitInvokeDynamicInsn(
                            "apply", "()L" + function + ";",
                            // Handle for the meta factory (I got most of these by looking what
                            // ASMs ClassReader gave me for a regularly compiled class
                            new Handle(
                                    H_INVOKESTATIC,
                                    lambdaMetafactory,
                                    "metafactory",
                                    "(L"+lookup+";L"+string+";L"+methodType+";L"+methodType+";L"+methodHandle+";L"+methodType+";)L"+callSite+";",
                                    false
                            ),
                            // Descriptor of Function#apply
                            Type.getMethodType("(L"+object+";)L"+object+";"),
                            // Handle for Script#getConfig
                            new Handle(
                                    H_INVOKEVIRTUAL,
                                    script,
                                    "getConfig",
                                    "()L"+config+";",
                                    false
                            ),
                            // Descriptor of Script#getConfig
                            Type.getMethodType("(L"+script+";)L"+config+";")
                    );
                    // Map the Optional with Script#getConfig
                    visitMethodInsn(INVOKEVIRTUAL, optional, "map", "(L"+function+";)L"+optional+";", false);

                    // Invoke Optional#orElse(null) and cast to Config
                    visitInsn(ACONST_NULL);
                    visitMethodInsn(INVOKEVIRTUAL, optional, "orElse", "(L"+object+";)L"+object+";", false);
                    visitTypeInsn(CHECKCAST, config);

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

        ClassVisitor triggerConstructorReplacer = new MethodWrappingVisitor(getCurrentScriptReplacer, mv -> new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                // 'imports'
                // so that I don't have to type all of them out each time
                String trigger = "ch/njol/skript/lang/Trigger";
                String file = "java/io/File";
                String string = "java/lang/String";
                String skriptEvent = "ch/njol/skript/lang/SkriptEvent";
                String list = "java/util/List";

                String script = "org/skriptlang/skript/lang/script/Script";

                String scriptLoader = "ch/njol/skript/ScriptLoader";
                String object = "java/lang/Object";
                String optional = "java/util/Optional";
                String function = "java/util/function/Function";

                String lambdaMetafactory = "java/lang/invoke/LambdaMetafactory";
                String lookup = "java/lang/invoke/MethodHandles$Lookup";
                String methodType = "java/lang/invoke/MethodType";
                String methodHandle = "java/lang/invoke/MethodHandle";
                String callSite = "java/lang/invoke/CallSite";

                /*
                For a (hopefully) readable overview of this bytecode, see `bytecode Trigger constructor overview.txt`
                 */

                if (owner.equals(trigger) && name.equals("<init>") && descriptor.equals(
                        "(L"+file+";L"+string+";L"+skriptEvent+";L"+list+";)V"
                )) {
                    // Create Object array of length 3
                    visitInsn(ICONST_3);
                    visitTypeInsn(ANEWARRAY, object);

                    // Back up other arguments in array
                    for (int i = 2; i >= 0; i--) {
                        visitInsn(DUP_X1);
                        visitInsn(SWAP);
                        visitLdcInsn(i);
                        visitInsn(SWAP);
                        visitInsn(AASTORE);
                    }

                    // Swap backup array and file arg
                    visitInsn(SWAP);

                    // File arg now on top

                    // Wrap in Optional
                    visitMethodInsn(INVOKESTATIC, optional, "ofNullable", "(L"+object+";)L"+optional+";", false);

                    // Method reference ScriptLoader::getScript
                    visitInvokeDynamicInsn(
                            "apply", "()L" + function + ";",
                            // Handle for the meta factory (I got most of these by looking what
                            // ASMs ClassReader gave me for a regularly compiled class
                            new Handle(
                                    H_INVOKESTATIC,
                                    lambdaMetafactory,
                                    "metafactory",
                                    "(L"+lookup+";L"+string+";L"+methodType+";L"+methodType+";L"+methodHandle+";L"+methodType+";)L"+callSite+";",
                                    false
                            ),
                            // Descriptor of Function#apply
                            Type.getMethodType("(L"+object+";)L"+object+";"),
                            // Handle for ScriptLoader.getScript
                            new Handle(
                                    H_INVOKESTATIC,
                                    scriptLoader,
                                    "getScript",
                                    "(L"+file+";)L"+script+";",
                                    false
                            ),
                            // Descriptor of ScriptLoader.getScript
                            Type.getMethodType("(L"+file+";)L"+script+";")
                    );
                    // Map the Optional with ScriptLoader#getScript
                    visitMethodInsn(INVOKEVIRTUAL, optional, "map", "(L"+function+";)L"+optional+";", false);

                    // Invoke Optional#orElse(null) and cast to Script
                    visitInsn(ACONST_NULL);
                    visitMethodInsn(INVOKEVIRTUAL, optional, "orElse", "(L"+object+";)L"+object+";", false);
                    visitTypeInsn(CHECKCAST, script);

                    // We now have a Script on top of the stack

                    visitInsn(SWAP);

                    // Restore the backed up values
                    for (int i = 0; i <= 2; i++) {
                        visitInsn(DUP);
                        visitLdcInsn(i);
                        visitInsn(AALOAD);

                        // Cast to appropriate type
                        String cast;
                        switch (i) {
                            case 0: {
                                cast = string;
                                break;
                            }
                            case 1: {
                                cast = skriptEvent;
                                break;
                            }
                            default: {
                                cast = list;
                                break;
                            }
                        }
                        visitTypeInsn(CHECKCAST, cast);

                        visitInsn(SWAP);
                    }

                    // Pop the array from stack
                    visitInsn(POP);

                    // Finally, invoke new Trigger constructor
                    visitMethodInsn(INVOKESPECIAL, trigger, "<init>",
                            "(L"+script+";L"+string+";L"+skriptEvent+";L"+list+";)V",
                            false
                    );

                    used.set(true);

                    return;
                }

                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
        });

        classReader.accept(triggerConstructorReplacer, 0);

        return classWriter.toByteArray();
    }

}
