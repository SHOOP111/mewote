package io.lattice.bridge;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marker reserved for the downstream build-time node validator; processor integration is not shipped yet. */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.TYPE})
public @interface PermissionNode {
    String value();
}
