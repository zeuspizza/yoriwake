package org.jacoco.agent.rt;

import java.nio.charset.StandardCharsets;

/**
 * A coverage agent whose execution data names the work that ran since the last take, so a record's
 * bytes say which window it holds.
 *
 * <p>Never empty, like JaCoCo's own dump, which always carries a header.
 */
public final class ScriptedAgent {

    private static final StringBuilder SINCE_LAST_TAKE = new StringBuilder();

    /** Marks work as having run, the way executing an instrumented class would. */
    public static void ran(String work) {
        synchronized (SINCE_LAST_TAKE) {
            if (SINCE_LAST_TAKE.length() > 0) {
                SINCE_LAST_TAKE.append(',');
            }
            SINCE_LAST_TAKE.append(work);
        }
    }

    public String getVersion() {
        return "scripted";
    }

    public byte[] getExecutionData(boolean reset) {
        synchronized (SINCE_LAST_TAKE) {
            String data = "#" + SINCE_LAST_TAKE;
            if (reset) {
                SINCE_LAST_TAKE.setLength(0);
            }
            return data.getBytes(StandardCharsets.UTF_8);
        }
    }
}
