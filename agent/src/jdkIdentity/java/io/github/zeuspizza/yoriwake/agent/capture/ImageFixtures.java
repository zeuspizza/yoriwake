package io.github.zeuspizza.yoriwake.agent.capture;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Writes, for the JDK it runs on, a patch of {@code java.base} holding one new class and a copy of
 * {@code java.compiler} holding one new class, for a test JVM to patch and upgrade its image with.
 * The copy is taken from that JDK's own image, since an upgrade must match the modules it joins.
 */
public final class ImageFixtures {

    private ImageFixtures() {}

    public static void main(String[] args) throws IOException {
        Path out = Paths.get(args[0]);
        write(out.resolve("patch"), "java/util/YoriwakePatched", classBytes("java/util/YoriwakePatched"));

        Path upgrade = out.resolve("upgrade").resolve("java.compiler");
        FileSystem image = FileSystems.getFileSystem(URI.create("jrt:/"));
        Path module = image.getPath("/modules/java.compiler");
        try (Stream<Path> files = Files.walk(module)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                Path target = upgrade.resolve(module.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        write(upgrade, "javax/tools/YoriwakeUpgraded", classBytes("javax/tools/YoriwakeUpgraded"));
    }

    private static void write(Path root, String internalName, byte[] bytes) throws IOException {
        Path file = root.resolve(internalName + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    /** The bytes of an empty public class named {@code internalName}. */
    static byte[] classBytes(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * A class whose {@code lookup()} returns a lookup in {@code Object} with full privilege, which
     * only code inside {@code java.base} can obtain.
     */
    static byte[] trampolineBytes(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor lookup = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lookup",
                "()Ljava/lang/invoke/MethodHandles$Lookup;", null, null);
        lookup.visitCode();
        lookup.visitLdcInsn(Type.getObjectType("java/lang/Object"));
        lookup.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup",
                "()Ljava/lang/invoke/MethodHandles$Lookup;", false);
        lookup.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandles", "privateLookupIn",
                "(Ljava/lang/Class;Ljava/lang/invoke/MethodHandles$Lookup;)Ljava/lang/invoke/MethodHandles$Lookup;",
                false);
        lookup.visitInsn(Opcodes.ARETURN);
        lookup.visitMaxs(0, 0);
        lookup.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
