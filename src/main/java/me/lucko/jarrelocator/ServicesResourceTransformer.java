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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

class ServicesResourceTransformer implements ResourceTransformer {
    private static final String SERVICES_PATH = "META-INF/services/";

    private final Map<String, Set<String>> serviceEntries = new LinkedHashMap<>();

    @Override
    public boolean shouldTransformResource(String resource) {
        return resource.startsWith(SERVICES_PATH);
    }

    @Override
    public void processResource(String resource, InputStream inputStream, Collection<Relocation> rules) throws IOException {
        String serviceClass = relocateIfPossible(resource.substring(SERVICES_PATH.length()), rules);
        Set<String> serviceLines = this.serviceEntries.computeIfAbsent(SERVICES_PATH + serviceClass, k -> new LinkedHashSet<>());

        String[] lines = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n"))
                .replace('\r', '|').replace('\n', '|').split("\\|");

        for (String line : lines) {
            if (!line.isEmpty()) {
                serviceLines.add(relocateIfPossible(line, rules));
            }
        }
    }

    private static String relocateIfPossible(String line, Collection<Relocation> rules) {
        for (Relocation rule : rules) {
            if (rule.canRelocateClass(line)) {
                return rule.relocateClass(line);
            }
        }
        return line;
    }

    @Override
    public void writeOutput(JarOutputStream jarOutputStream) throws IOException {
        this.serviceEntries.values().removeIf(Set::isEmpty);
        if (this.serviceEntries.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Set<String>> entry : this.serviceEntries.entrySet()) {
            jarOutputStream.putNextEntry(new JarEntry(entry.getKey()));

            StringBuilder builder = new StringBuilder();
            for (String line : entry.getValue()) {
                builder.append(line).append('\n');
            }
            jarOutputStream.write(builder.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

}
