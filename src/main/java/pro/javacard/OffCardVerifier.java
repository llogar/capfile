/*
 * Copyright (c) 2018 Martin Paljak
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package pro.javacard;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Enumeration;
import java.util.Vector;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class OffCardVerifier {

    private final JavaCardSDK sdk;

    public static OffCardVerifier forSDK(JavaCardSDK sdk) {
        // Only main method in 2.1 SDK
        if (sdk.getVersion().equals(JavaCardSDK.Version.V21))
            throw new RuntimeException("Verification is supported with JavaCard SDK 2.2.1 or later");
        return new OffCardVerifier(sdk);
    }

    private OffCardVerifier(JavaCardSDK sdk) {
        this.sdk = sdk;
        System.out.println("Verifying with " + sdk.getRelease());
        // Warn about recommended usage
        if (!sdk.getRelease().equals("3.0.5u3")) {
            System.err.println("NB! Please use at least JavaCard SDK 3.0.5u3 when verifying!");
        }
    }

    public void verify(File f, Vector<File> exps) throws VerifierError {
        File tmp = makeTemp();
        try {
            CAPFile cap = CAPFile.fromStream(new FileInputStream(f));

            // Get verifier class
            Class verifier = Class.forName("com.sun.javacard.offcardverifier.Verifier", true, sdk.getClassLoader());

            // SDK
            exps.add(sdk.getExportDir());

            final Vector<File> expfiles = new Vector<>();
            for (File e : exps) {
                // collect all export files to a list
                if (e.isDirectory()) {
                    Files.walkFileTree(e.toPath(), new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(java.nio.file.Path file, BasicFileAttributes attrs)
                                throws IOException {
                            if (file.toString().endsWith(".exp")) {
                                expfiles.add(file.toFile());
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } else if (e.isFile()) {
                    if (e.toString().endsWith(".exp")) {
                        expfiles.add(e);
                    } else if (e.toString().endsWith(".jar")) {
                        expfiles.addAll(extractExps(e, tmp));
                    }
                }
            }

            String packagename = cap.getPackageName();

            try (FileInputStream input = new FileInputStream(f)) {
                // 3.0.5u1 still uses old signature
                if (sdk.getRelease().equals("3.0.5u3") || sdk.getRelease().equals("3.0.5u2")) {
                    Method m = verifier.getMethod("verifyCap", File.class, String.class, Vector.class);
                    m.invoke(null, f, packagename, expfiles);
                } else {
                    Method m = verifier.getMethod("verifyCap", FileInputStream.class, String.class, Vector.class);
                    m.invoke(null, input, packagename, expfiles);
                }
            } catch (InvocationTargetException e) {
                throw new VerifierError(e.getTargetException().getMessage(), e.getTargetException());
            }
            System.out.println("Verified " + expfiles);
        } catch (ReflectiveOperationException | IOException e) {
            throw new RuntimeException("Could not run verifier: " + e.getMessage());
        } finally {
            // Clean extracted exps
            rmminusrf(tmp.toPath());
        }
    }


    private static void rmminusrf(Path path) {
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e)
                        throws IOException {
                    if (e == null) {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    } else {
                        // directory iteration failed
                        throw e;
                    }
                }
            });
        } catch (FileNotFoundException | NoSuchFileException e) {
            // Already gone - do nothing.
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    
    private File makeTemp() {
        try {
            return Files.createTempDirectory("capfile").toFile();
        } catch (IOException e) {
            throw new RuntimeException("Can not make temporary folder", e);
        }
    }

    private static Vector<File> extractExps(File in, File out) throws IOException {
        Vector<File> exps = new Vector<>();
        try (JarFile jarfile = new JarFile(in)) {
            Enumeration<JarEntry> entries = jarfile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().toLowerCase().endsWith(".exp")) {
                    File f = new File(out, entry.getName());
                    if (!f.exists()) {
                        if (!f.getParentFile().mkdirs())
                            throw new IOException("Failed to create folder: " + f.getParentFile());
                        f = new File(out, entry.getName());
                    }
                    try (InputStream is = jarfile.getInputStream(entry);
                         FileOutputStream fo = new java.io.FileOutputStream(f)) {
                        byte[] buf = new byte[1024];
                        while (true) {
                            int r = is.read(buf);
                            if (r == -1) {
                                break;
                            }
                            fo.write(buf, 0, r);
                        }
                    }
                    exps.add(f);
                }
            }
        }
        return exps;
    }
}
