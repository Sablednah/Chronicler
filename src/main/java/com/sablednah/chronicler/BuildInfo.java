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

    /** The four values, as read; {@code unknown} for anything missing. */
    public record Stamp(String commit, String branch, String time, String version) {}

    /**
     * Read a stamp from a stream that may be null, empty or garbage: the answer is
     * always a stamp and never an exception, because this is diagnostic, not a
     * dependency. Package-visible so the self-test can run the missing and the
     * malformed cases instead of trusting the sentence above.
     */
    public static Stamp parse(InputStream in) {
        String commit = "unknown", branch = "unknown", time = "unknown", version = "unknown";
        try {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                commit = p.getProperty("commit", commit);
                branch = p.getProperty("branch", branch);
                time = p.getProperty("time", time);
                version = p.getProperty("version", version);
            }
        } catch (Exception ignored) {
            // fall through with what was read, or unknowns
        }
        return new Stamp(commit, branch, time, version);
    }

    static {
        Stamp s;
        try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            s = parse(in);
        } catch (Exception e) {
            s = new Stamp("unknown", "unknown", "unknown", "unknown");
        }
        COMMIT = s.commit(); BRANCH = s.branch(); TIME = s.time(); VERSION = s.version();
    }

    /** The one-line form of any stamp. */
    public static String describe(Stamp s) {
        return s.version() + " (build " + s.commit() + " on " + s.branch() + ", " + s.time() + ")";
    }

    public static String commit() { return COMMIT; }
    public static String branch() { return BRANCH; }
    public static String time() { return TIME; }
    public static String version() { return VERSION; }

    /** {@code 0.1.0 (build a1b2c3d4 on main, 2026-09-10T09:15:00Z)}; {@code -dirty} means uncommitted changes. */
    public static String describe() {
        return describe(new Stamp(COMMIT, BRANCH, TIME, VERSION));
    }

    private BuildInfo() {}
}
