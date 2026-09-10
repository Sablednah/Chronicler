package com.sablednah.chronicler;

import java.io.InputStream;
import java.util.Properties;

/**
 * Which build this is -- the family's build stamp (LegendQuest has the reference).
 * A version number answers "which release"; during development that is a different
 * question from "which bytes". Read from a generated properties file, not the
 * manifest, because a dev run has no jar; a missing stamp reads as {@code unknown}
 * and never stops the mod loading. The startup log line is the half that matters:
 * it says what actually ran.
 */
public final class BuildInfo {

    private static final String RESOURCE = "/chronicler/build.properties";

    private static final String COMMIT;
    private static final String BRANCH;
    private static final String TIME;
    private static final String VERSION;

    static {
        String commit = "unknown", branch = "unknown", time = "unknown", version = "unknown";
        try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                commit = p.getProperty("commit", commit);
                branch = p.getProperty("branch", branch);
                time = p.getProperty("time", time);
                version = p.getProperty("version", version);
            }
        } catch (Exception ignored) {
            // diagnostic, not a dependency
        }
        COMMIT = commit; BRANCH = branch; TIME = time; VERSION = version;
    }

    public static String commit() { return COMMIT; }
    public static String branch() { return BRANCH; }
    public static String time() { return TIME; }
    public static String version() { return VERSION; }

    /** {@code 0.1.0 (build a1b2c3d4 on main, 2026-09-10T09:15:00Z)}; {@code -dirty} means uncommitted changes. */
    public static String describe() {
        return VERSION + " (build " + COMMIT + " on " + BRANCH + ", " + TIME + ")";
    }

    private BuildInfo() {}
}
