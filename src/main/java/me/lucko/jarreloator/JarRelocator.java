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

package me.lucko.jarreloator;

import org.apache.commons.io.IOUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JarRelocator {
    private final File input;
    private final File output;
    private final Collection<Relocation> relocations;

    public JarRelocator(File input, File output, Collection<Relocation> relocations) {
        this.input = input;
        this.output = output;
        this.relocations = relocations;
    }

    public void run() throws IOException {
        Set<String> resources = new HashSet<>();
        RelocatingRemapper remapper = new RelocatingRemapper(relocations);

        try (JarOutputStream out = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) {
            JarFile jarFile = new JarFile(input);

            for (Enumeration<JarEntry> j = jarFile.entries(); j.hasMoreElements(); ) {
                JarEntry entry = j.nextElement();
                String name = entry.getName();

                if (name.equals("META-INF/INDEX.LIST")) {
                    // we cannot allow the jar indexes to be copied over or the
                    // jar is useless. Ideally, we could create a new one
                    // later
                    continue;
                }

                if (entry.isDirectory()) {
                    continue;
                }

                try (InputStream in = jarFile.getInputStream(entry)) {
                    String mappedName = remapper.map(name);

                    int idx = mappedName.lastIndexOf('/');
                    if (idx != -1) {
                        // make sure dirs are created
                        String dir = mappedName.substring(0, idx);
                        if (!resources.contains(dir)) {
                            addDirectory(resources, out, dir);
                        }
                    }

                    if (name.endsWith(".class")) {
                        addRemappedClass(remapper, out, name, in);
                    } else {
                        // Avoid duplicates that aren't accounted for by the resource transformers
                        if (resources.contains(mappedName)) {
                            return;
                        }

                        addResource(resources, out, mappedName, entry.getTime(), in);
                    }
                }
            }

            out.close();
        }
    }

    private void addDirectory(Set<String> resources, JarOutputStream jos, String name) throws IOException {
        if (name.lastIndexOf('/') > 0) {
            String parent = name.substring(0, name.lastIndexOf('/'));
            if (!resources.contains(parent)) {
                addDirectory(resources, jos, parent);
            }
        }

        // directory entries must end in "/"
        JarEntry entry = new JarEntry(name + "/");
        jos.putNextEntry(entry);

        resources.add(name);
    }

    private void addResource(Set<String> resources, JarOutputStream jos, String name, long lastModified, InputStream is) throws IOException {
        JarEntry jarEntry = new JarEntry(name);
        jarEntry.setTime(lastModified);

        jos.putNextEntry(jarEntry);
        IOUtils.copy(is, jos);

        resources.add(name);
    }

    private void addRemappedClass(RelocatingRemapper remapper, JarOutputStream out, String name, InputStream in) throws IOException {
        ClassReader cr = new ClassReader(in);

        // We don't pass the ClassReader here. This forces the ClassWriter to rebuild the constant pool.
        // Copying the original constant pool should be avoided because it would keep references
        // to the original class names. This is not a problem at runtime (because these entries in the
        // constant pool are never used), but confuses some tools that use the constant pool to determine
        // the dependencies of a class.
        ClassWriter cw = new ClassWriter(0);

        final String pkg = name.substring(0, name.lastIndexOf('/') + 1);
        ClassVisitor cv = new ClassRemapper(cw, remapper) {
            @Override
            public void visitSource(final String source, final String debug) {
                if (source == null) {
                    super.visitSource(source, debug);
                } else {
                    final String fqSource = pkg + source;
                    final String mappedSource = remapper.map(fqSource);
                    final String filename = mappedSource.substring(mappedSource.lastIndexOf('/') + 1);
                    super.visitSource(filename, debug);
                }
            }
        };

        try {
            cr.accept(cv, ClassReader.EXPAND_FRAMES);
        } catch (Throwable ise) {
            throw new RuntimeException("Error in ASM processing class " + name, ise);
        }

        byte[] renamedClass = cw.toByteArray();

        // Need to take the .class off for remapping evaluation
        String mappedName = remapper.map(name.substring(0, name.indexOf('.')));

        // Now we put it back on so the class file is written out with the right extension.
        out.putNextEntry(new JarEntry(mappedName + ".class"));
        IOUtils.write(renamedClass, out);
    }

    private static class RelocatingRemapper extends Remapper {
        private final Pattern classPattern = Pattern.compile("(\\[*)?L(.+);");

        Collection<Relocation> relocations;

        private RelocatingRemapper(Collection<Relocation> relocations) {
            this.relocations = relocations;
        }

        public Object mapValue(Object object) {
            if (object instanceof String) {
                String name = (String) object;
                String value = name;

                String prefix = "";
                String suffix = "";

                Matcher m = classPattern.matcher(name);
                if (m.matches()) {
                    prefix = m.group(1) + "L";
                    suffix = ";";
                    name = m.group(2);
                }

                for (Relocation r : relocations) {
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

        public String map(String name) {
            String value = name;

            String prefix = "";
            String suffix = "";

            Matcher m = classPattern.matcher(name);
            if (m.matches()) {
                prefix = m.group(1) + "L";
                suffix = ";";
                name = m.group(2);
            }

            for (Relocation r : relocations) {
                if (r.canRelocatePath(name)) {
                    value = prefix + r.relocatePath(name) + suffix;
                    break;
                }
            }

            return value;
        }

    }
}
