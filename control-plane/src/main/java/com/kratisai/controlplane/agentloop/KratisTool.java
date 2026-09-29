package com.kratisai.controlplane.agentloop;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.annotation.AliasFor;

/**
 * Marks a method as a Kratis tool.
 *
 * <p>Composed on top of Spring AI's {@link Tool} so that every result is converted by {@link
 * KratisToolResultConverter}. Use this instead of {@link Tool}: raw {@code @Tool} bypasses the
 * converter and lets JSON-Schema reference keys reach Gemini as function-response references. The
 * ArchUnit rule {@code TOOLS_USE_KRATIS_TOOL_ANNOTATION} rejects raw {@code @Tool}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tool(resultConverter = KratisToolResultConverter.class)
public @interface KratisTool {

    @AliasFor(annotation = Tool.class, attribute = "name")
    String name() default "";

    @AliasFor(annotation = Tool.class, attribute = "description")
    String description() default "";

    @AliasFor(annotation = Tool.class, attribute = "returnDirect")
    boolean returnDirect() default false;
}
