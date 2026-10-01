// AGP 8.5.2, not 8.1.x: 8.1 with a JDK 21 daemon fails in `JdkImageTransform` when jlink runs
// against a newer JDK than it expects. That failure names androidJdkImage and core-for-system-
// modules.jar and looks like a project problem; it is a toolchain version mismatch.
plugins {
    id("com.android.library") version "8.5.2" apply false
}
