package io.github.zeuspizza.yoriwake.capturescript;

import java.lang.reflect.Proxy;
import org.testng.IClass;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;

/** The slice of a TestNG result the TestNG listener reads, without running TestNG. */
final class TestNgResults {

    private TestNgResults() {}

    static ITestResult of(String className, String method) {
        ClassLoader loader = TestNgResults.class.getClassLoader();
        Object testClass = Proxy.newProxyInstance(loader, new Class<?>[] {IClass.class},
                (proxy, m, args) -> "getName".equals(m.getName()) ? className : null);
        Object testMethod = Proxy.newProxyInstance(loader, new Class<?>[] {ITestNGMethod.class},
                (proxy, m, args) -> "getMethodName".equals(m.getName()) ? method : null);
        return (ITestResult) Proxy.newProxyInstance(loader, new Class<?>[] {ITestResult.class},
                (proxy, m, args) -> {
                    switch (m.getName()) {
                        case "getTestClass":
                            return testClass;
                        case "getMethod":
                            return testMethod;
                        default:
                            return null;
                    }
                });
    }
}
