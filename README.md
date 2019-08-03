# jar-relocator
[![Javadocs](https://javadoc.io/badge/me.lucko/jar-relocator.svg)](https://javadoc.io/doc/me.lucko/jar-relocator)
[![Maven Central](https://img.shields.io/maven-metadata/v/https/repo1.maven.org/maven2/me/lucko/jar-relocator/maven-metadata.xml.svg?label=maven%20central&colorB=brightgreen)](https://search.maven.org/artifact/me.lucko/jar-relocator)

A Java program to relocate classes within a jar archive using ASM. (effectively a standalone implementation of the relocation functionality provided by the [maven-shade-plugin](https://maven.apache.org/plugins/maven-shade-plugin/).)

The use of shading allows Java programs to include all dependencies in one "uber" jar archive - meaning the dependency is available for use at runtime. However, directly including classes can cause conflicts due to duplicate copies of the same class potentially being present on the classpath.

To address this issue, one can relocate the "shaded" classes in order to create a private copy of their bytecode, and prevent potential conflicts.

### Why not just use the maven-shade-plugin?

An alternative to creating an "uber" jar is to download copies of the required dependencies and add them to the classpath at runtime. This means one does not have to distribute large jar archives, but makes it infeasible to utilise maven-shade relocations (without self-hosting relocated copies of the dependencies), reintroducing the potential for the conflict issues explained previously.

A solution to this is to have the client apply the relocations too, after downloading the dependency from the official source.

### Usage

jar-relocator is [available from Maven Central, group id: `me.lucko`, artifact id: `jar-relocator`](https://search.maven.org/artifact/me.lucko/jar-relocator).

jar relocator has two dependencies: ASM and ASM Commons. These are required at runtime.


```java
import me.lucko.jarrelocator.JarRelocator;
import me.lucko.jarrelocator.Relocation;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Bootstrap {

    public static void main(String[] args) {
        File input = new File("input.jar");
        File output = new File("output.jar");

        List<Relocation> rules = new ArrayList<>();
        rules.add(new Relocation("com.google.common", "me.lucko.test.lib.guava"));
        rules.add(new Relocation("com.google.gson", "me.lucko.test.lib.gson"));

        JarRelocator relocator = new JarRelocator(input, output, rules);

        try {
            relocator.run();
        } catch (IOException e) {
            throw new RuntimeException("Unable to relocate", e);
        }
    }

}
```