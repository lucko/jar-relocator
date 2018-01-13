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

public final class Relocation {

    private final String pattern;
    private final String relocatedPattern;
    private final String pathPattern;
    private final String relocatedPathPattern;

    public Relocation(String pattern, String relocatedPattern) {
        this.pattern = pattern.replace('/', '.');
        this.pathPattern = pattern.replace('.', '/');
        this.relocatedPattern = relocatedPattern.replace('/', '.');
        this.relocatedPathPattern = relocatedPattern.replace('.', '/');
    }

    boolean canRelocatePath(String path) {
        if (path.endsWith(".class")) {
            path = path.substring(0, path.length() - 6);
        }

        // Allow for annoying option of an extra / on the front of a path. See MSHADE-119; comes from
        // getClass().getResource("/a/b/c.properties").
        return path.startsWith(pathPattern) || path.startsWith("/" + pathPattern);
    }

    boolean canRelocateClass(String clazz) {
        return clazz.indexOf('/') < 0 && canRelocatePath(clazz.replace('.', '/'));
    }

    String relocatePath(String path) {
        return path.replaceFirst(pathPattern, relocatedPathPattern);
    }

    String relocateClass(String clazz) {
        return clazz.replaceFirst(pattern, relocatedPattern);
    }

    String applyToSourceContent(String sourceContent) {
        return sourceContent.replaceAll("\\b" + pattern, relocatedPattern);
    }
}
