package com.example.idempotency.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    String headerName() default "Idempotency-Key";
    String keyPrefix() default "";
    long ttl() default 1;
    TimeUnit timeUnit() default TimeUnit.HOURS;
    boolean mandatory() default true;
    boolean includeBody() default false;
}
