package fixture;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SharedNames.class)
class SharedDisplayNamesTest {
    @TestTemplate
    void a(int v) {
    }

    @TestTemplate
    void b(int v) {
        if (v == 1 && !System.getProperty("fixture.fail", "").isEmpty()) {
            fail("planted failure in b at v = 1");
        }
    }

    @Test
    void c() {
    }

    @TestFactory
    Stream<DynamicTest> d() {
        return Stream.of(dynamicTest("x", () -> { }), dynamicTest("y", () -> { }));
    }
}
