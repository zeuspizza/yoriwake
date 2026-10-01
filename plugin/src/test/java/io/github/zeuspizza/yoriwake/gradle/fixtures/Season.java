package io.github.zeuspizza.yoriwake.gradle.fixtures;

/** An enum whose constructor runs code. */
public enum Season {
    SUMMER("long"),
    WINTER(System.lineSeparator());

    public final String note;

    Season(String note) {
        this.note = note;
    }
}
