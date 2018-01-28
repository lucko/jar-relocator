/*
 * Copyright Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.lucko.jarrelocator;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Relocates classes within a JAR archive.
 */
public final class JarRelocator {

    /** The input jar */
    private final File input;
    /** The output jar */
    private final File output;
    /** The relocation rules */
    private final Collection<Relocation> rules;

    /**
     * Creates a new instance with the given settings.
     *
     * @param input the input jar file
     * @param output the output jar file
     * @param rules the relocation rules
     */
    public JarRelocator(File input, File output, Collection<Relocation> rules) {
        this.input = input;
        this.output = output;
        this.rules = rules;
    }

    /**
     * Creates a new instance with the given settings.
     *
     * @param input the input jar file
     * @param output the output jar file
     * @param rules the relocation rules
     */
    public JarRelocator(File input, File output, Map<String, String> rules) {
        this.input = input;
        this.output = output;
        this.rules = new ArrayList<>(rules.size());
        for (Map.Entry<String, String> entry : rules.entrySet()) {
            this.rules.add(new Relocation(entry.getKey(), entry.getValue()));
        }
    }

    /**
     * Executes the relocation task
     *
     * @throws IOException if an exception is encountered whilst performing i/o
     *                     with the input or output file
     */
    public void run() throws IOException {
        Set<String> resources = new HashSet<>();
        Relocator relocator = new Relocator(rules);

        try (JarOutputStream jarOut = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(output))); JarFile jarIn = new JarFile(input)) {
            for (Enumeration<JarEntry> entries = jarIn.entries(); entries.hasMoreElements(); ) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                if (name.equals("META-INF/INDEX.LIST") || entry.isDirectory()) {
                    continue;
                }

                String mappedName = relocator.map(name);

                try (InputStream entryIn = jarIn.getInputStream(entry)) {
                    int index = mappedName.lastIndexOf('/');
                    if (index != -1) {
                        // make sure dirs are created
                        String dir = mappedName.substring(0, index);
                        if (!resources.contains(dir)) {
                            addDirectory(resources, jarOut, dir);
                        }
                    }

                    if (name.endsWith(".class")) {
                        addRelocatedClass(relocator, name, entryIn, jarOut);
                    } else {
                        // avoid duplicates
                        if (resources.contains(mappedName)) {
                            return;
                        }

                        addResource(resources, mappedName, entry.getTime(), entryIn, jarOut);
                    }
                }
            }
        }
    }

    private static void addDirectory(Set<String> resources, JarOutputStream jarOut, String name) throws IOException {
        if (name.lastIndexOf('/') > 0) {
            String parent = name.substring(0, name.lastIndexOf('/'));
            if (!resources.contains(parent)) {
                addDirectory(resources, jarOut, parent);
            }
        }

        // directory entries must end in "/"
        JarEntry entry = new JarEntry(name + "/");
        jarOut.putNextEntry(entry);

        resources.add(name);
    }

    private static void addResource(Set<String> resources, String name, long lastModified, InputStream entryIn, JarOutputStream jarOut) throws IOException {
        JarEntry jarEntry = new JarEntry(name);
        jarEntry.setTime(lastModified);

        jarOut.putNextEntry(jarEntry);
        copy(entryIn, jarOut);

        resources.add(name);
    }

    private static void addRelocatedClass(Relocator relocator, String name, InputStream entryIn, JarOutputStream jarOut) throws IOException {
        ClassReader classReader = new ClassReader(entryIn);
        ClassWriter classWriter = new ClassWriter(0);
        RelocatingClassVisitor classVisitor = new RelocatingClassVisitor(classWriter, relocator, name);

        try {
            classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES);
        } catch (Throwable e) {
            throw new RuntimeException("Error processing class " + name, e);
        }

        byte[] renamedClass = classWriter.toByteArray();

        // Need to take the .class off for remapping evaluation
        String mappedName = relocator.map(name.substring(0, name.indexOf('.')));

        // Now we put it back on so the class file is written out with the right extension.
        jarOut.putNextEntry(new JarEntry(mappedName + ".class"));
        jarOut.write(renamedClass);
    }

    private static void copy(InputStream from, OutputStream to) throws IOException {
        byte[] buf = new byte[8192];
        while (true) {
            int n = from.read(buf);
            if (n == -1) {
                break;
            }
            to.write(buf, 0, n);
        }
    }

    private static final class RelocatingClassVisitor extends ClassRemapper {
        private final String pkg;

        private RelocatingClassVisitor(ClassWriter writer, Remapper remapper, String name) {
            super(writer, remapper);
            this.pkg = name.substring(0, name.lastIndexOf('/') + 1);
        }

        @Override
        public void visitSource(String source, String debug) {
            if (source == null) {
                super.visitSource(null, debug);
            } else {
                String fqSource = this.pkg + source;
                String mappedSource = super.remapper.map(fqSource);
                String filename = mappedSource.substring(mappedSource.lastIndexOf('/') + 1);
                super.visitSource(filename, debug);
            }
        }
    }

    private static class Relocator extends Remapper {
        private static final Pattern CLASS_PATTERN = Pattern.compile("(\\[*)?L(.+);");

        private final Collection<Relocation> rules;

        private Relocator(Collection<Relocation> rules) {
            this.rules = rules;
        }

        @Override
        public Object mapValue(Object object) {
            if (object instanceof String) {
                String name = (String) object;
                String value = name;

                String prefix = "";
                String suffix = "";

                Matcher m = CLASS_PATTERN.matcher(name);
                if (m.matches()) {
                    prefix = m.group(1) + "L";
                    suffix = ";";
                    name = m.group(2);
                }

                for (Relocation r : this.rules) {
                    if (r.canRelocateClass(name)) {
                        value = prefix + r.relocateClass(name) + suffix;
                        break;
                    } else if (r.canRelocatePath(name)) {
                        value = prefix + r.relocatePath(name) + suffix;
                        break;
                    }
                }

                return value;
            }

            return super.mapValue(object);
        }

        @Override
        public String map(String name) {
            String value = name;

            String prefix = "";
            String suffix = "";

            Matcher m = CLASS_PATTERN.matcher(name);
            if (m.matches()) {
                prefix = m.group(1) + "L";
                suffix = ";";
                name = m.group(2);
            }

            for (Relocation r : this.rules) {
                if (r.canRelocatePath(name)) {
                    value = prefix + r.relocatePath(name) + suffix;
                    break;
                }
            }

            return value;
        }
    }
}
