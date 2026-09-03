package io.majo.harness.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record component as optional on the wire (absent fields are legal);
 * the TypeScript type generator turns annotated components into
 * {@code field?} properties.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface OptionalWire {}
