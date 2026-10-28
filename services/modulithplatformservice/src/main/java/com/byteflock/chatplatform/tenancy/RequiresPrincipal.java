package com.byteflock.chatplatform.tenancy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Requires a verified {@link Caller} of the given type on a controller method (or every method of a controller).
 * <p>
 * The interceptor acts only on annotated handlers, so public routes (widget session start, agent local login, realtime
 * health) stay unauthenticated without an exclusion list. A missing or invalid token is 401 {@code UNAUTHENTICATED};
 * a token of another principal type is 403 {@code FORBIDDEN}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.TYPE })
public @interface RequiresPrincipal {

	PrincipalType value();

}
