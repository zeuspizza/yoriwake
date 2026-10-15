package fixture;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;

/** Invokes every template of the class at `v = 1` and `v = 2`: display names that name no method. */
public class SharedNames implements TestTemplateInvocationContextProvider {
    @Override
    public boolean supportsTestTemplate(ExtensionContext context) {
        return true;
    }

    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
        return Stream.of(1, 2).map(SharedNames::invocation);
    }

    private static TestTemplateInvocationContext invocation(int value) {
        return new TestTemplateInvocationContext() {
            @Override
            public String getDisplayName(int invocationIndex) {
                return "v = " + value;
            }

            @Override
            public List<org.junit.jupiter.api.extension.Extension> getAdditionalExtensions() {
                return List.of(new ParameterResolver() {
                    @Override
                    public boolean supportsParameter(ParameterContext parameter, ExtensionContext extension) {
                        return parameter.getParameter().getType() == int.class;
                    }

                    @Override
                    public Object resolveParameter(ParameterContext parameter, ExtensionContext extension) {
                        return value;
                    }
                });
            }
        };
    }
}
