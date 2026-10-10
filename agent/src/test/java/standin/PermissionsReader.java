package standin;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.security.PermissionCollection;

/**
 * A loader whose {@code getPermissions}, which the JDK calls while it defines a class, runs code of
 * its own first: project code running inside a class definition. Outside the agent's packages, so
 * its frame stands between that code and the definition as a project loader's would.
 */
public final class PermissionsReader extends URLClassLoader {

    private final Runnable during;

    public PermissionsReader(File directory, Runnable during) throws MalformedURLException {
        super(new URL[] {directory.toURI().toURL()}, null);
        this.during = during;
    }

    @Override
    protected PermissionCollection getPermissions(CodeSource codesource) {
        during.run();
        return super.getPermissions(codesource);
    }
}
