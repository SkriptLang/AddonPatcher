package org.skriptlang.addonpatcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.jar.JarEntry;

public class Util {

    /**
     * Creates a new {@link JarEntry}, with many attributes copied over:
     * <ul>
     *     <li>Name</li>
     *     <li>Comment</li>
     *     <li>Creation time</li>
     *     <li>Last access time</li>
     *     <li>Last modification time</li>
     * </ul>
     */
    public static JarEntry newJarEntry(JarEntry oldEntry) {
        JarEntry newEntry = new JarEntry(oldEntry.getName());
        if (oldEntry.getComment() != null)
            newEntry.setComment(oldEntry.getComment());
        if (oldEntry.getCreationTime() != null)
            newEntry.setCreationTime(oldEntry.getCreationTime());
        if (oldEntry.getLastAccessTime() != null)
            newEntry.setLastAccessTime(oldEntry.getLastAccessTime());
        if (oldEntry.getLastModifiedTime() != null)
            newEntry.setLastModifiedTime(oldEntry.getLastModifiedTime());
        if (oldEntry.getTime() != -1)
            newEntry.setTime(oldEntry.getTime());
        return newEntry;
    }

    /**
     * Write every byte from the given {@link InputStream} to the given {@link OutputStream}.
     * Doesn't close either of the given streams.
     */
    public static void transferStreams(InputStream inputStream, OutputStream outputStream) throws IOException {
        byte[] bytes = new byte[1024];
        int length;
        while ((length = inputStream.read(bytes)) != -1) {
            outputStream.write(bytes, 0, length);
        }
    }

    /**
     * Reads every byte from the given {@link InputStream} and creates a byte array containing them.
     */
    public static byte[] readAll(InputStream inputStream) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        transferStreams(inputStream, byteArrayOutputStream);
        return byteArrayOutputStream.toByteArray();
    }

}
