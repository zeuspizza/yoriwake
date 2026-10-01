package io.github.zeuspizza.yoriwake.gradle.fixtures;

/** A static method with real logic and nothing else. */
public class HasLogic {
    public static String slashes(String path) {
        return path.replace(java.io.File.separatorChar, '/');
    }
}
