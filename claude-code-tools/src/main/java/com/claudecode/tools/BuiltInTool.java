package com.claudecode.tools;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares immutable class-level metadata for one statically defined tool.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BuiltInTool {

    String name();

    String[] aliases() default {};

    boolean shouldDefer() default false;

    boolean strict() default false;

    int maxResultSizeChars() default 100_000;

    boolean readOnly() default false;

    boolean concurrencySafe() default false;
}
