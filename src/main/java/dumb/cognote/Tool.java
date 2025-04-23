package dumb.cognote;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an LLM tool.
 * The method must be public and reside in a class managed by the system (e.g., LM.Tools).
 * Parameters and return types should be compatible with JSON serialization/deserialization
 * handled by the LLM integration library (LangChain4j).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {
    /**
     * The name of the tool, used by the LLM. Should be descriptive and snake_case.
     */
    String name();

    /**
     * A description of the tool's purpose and how to use it, for the LLM.
     */
    String description();
}
