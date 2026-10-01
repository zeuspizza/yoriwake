package io.github.zeuspizza.yoriwake.agent.capture;

import io.github.zeuspizza.yoriwake.agent.host.AttachedLoader;

/**
 * The JVM-wide claim on capture: one capturing engine per JVM.
 *
 * <p>Every take of coverage is a JVM-global reset, so a second capture in the same JVM -- a nested
 * Launcher's listener, or TestNG's own listener beside the Platform's TestNG engine -- would empty
 * the first one's windows and could reopen its record directory. The loser captures nothing, and
 * its tests' coverage lands on the owner's open test, which is the safe direction.
 *
 * <p>A system property rather than a static, so a nested Launcher's listener in the attached
 * copy sees the claim too. A copy of this agent that another class loader defined never gets the
 * claim, even when nothing holds it: it would record under the attached copy's worker directory.
 * See {@link AttachedLoader}.
 */
public final class CaptureClaim {

    public static final String PROPERTY = "yoriwake.internal.capture.owner";

    private static final boolean ATTACHED = AttachedLoader.attached(CaptureClaim.class);

    private final String token;

    private CaptureClaim(Class<?> owner) {
        this.token = owner.getName() + "@" + System.identityHashCode(this) + ":" + System.nanoTime();
    }

    /** The claim for a capture of type {@code owner}, or null when another capture holds it. */
    public static CaptureClaim take(Class<?> owner) {
        if (!ATTACHED || !AttachedLoader.attached(owner)) {
            return null;
        }
        CaptureClaim claim = new CaptureClaim(owner);
        return System.getProperties().putIfAbsent(PROPERTY, claim.token) == null ? claim : null;
    }

    public void release() {
        System.getProperties().remove(PROPERTY, token);
    }
}
