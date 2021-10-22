# jar-relocator
[![Javadocs](https://javadoc.io/badge/me.lucko/jar-relocator.svg)](https://javadoc.io/doc/me.lucko/jar-relocator)
[![Maven Central](https://img.shields.io/maven-metadata/v/https/repo1.maven.org/maven2/me/lucko/jar-relocator/maven-metadata.xml.svg?label=maven%20central&colorB=brightgreen)](https://search.maven.org/artifact/me.lucko/jar-relocator)

A Java program to relocate classes within a jar archive using ASM. (effectively a standalone implementation of the relocation functionality provided by the [maven-shade-plugin](https://maven.apache.org/plugins/maven-shade-plugin/).)

Shading allows Java developers to include both their app *and* library dependencies in one "uber" jar archive. This means dependencies are always available without needing to append them onto the classpath manually. However, this can cause problems if duplicate copies of the same class are on the classpath at the same time.

To address this issue, you can relocate the "shaded" classes to your own package/namespace in order to prevent conflicts.

### Why not just use the maven-shade-plugin?

If you can relocate at build time - you should.

However, there are times where you might want to relocate at runtime, or at some other stage. My usecase is the app downloading dependencies dynamically from a repository on first startup, and needing to relocate then.

### Usage

jar-relocator is [available from Maven Central, group id: `me.lucko`, artifact id: `jar-relocator`](https://search.maven.org/artifact/me.lucko/jar-relocator).

jar relocator has two dependencies: ASM and ASM Commons.


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
