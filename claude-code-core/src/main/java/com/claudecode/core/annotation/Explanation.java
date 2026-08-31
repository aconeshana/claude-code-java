package com.claudecode.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({
    ElementType.TYPE,
    ElementType.METHOD,
    ElementType.CONSTRUCTOR,
    ElementType.FIELD,
    ElementType.PARAMETER,
    ElementType.LOCAL_VARIABLE,
    ElementType.RECORD_COMPONENT,
    ElementType.TYPE_USE,
    ElementType.PACKAGE
})
public @interface Explanation {

    /** Brief reason why this declaration intentionally differs from the original. */
    String value();
}
