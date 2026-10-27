package com.byteflock.chatplatform.tenancy.internal;

import com.byteflock.chatplatform.tenancy.Caller;
import com.byteflock.chatplatform.tenancy.LocalDevProperties;
import com.byteflock.chatplatform.tenancy.SessionTokens;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the tenancy web layer: the caller interceptor on the authenticated API trees and the {@link Caller} argument
 * resolver. Public routes (widget session start, agent local login) are not annotated, so the interceptor ignores them.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LocalDevProperties.class)
class TenancyWebConfig implements WebMvcConfigurer {

	private final AuthInterceptor authInterceptor;

	TenancyWebConfig(SessionTokens sessionTokens, LocalDevProperties localDevProperties) {
		this.authInterceptor = new AuthInterceptor(sessionTokens, localDevProperties);
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(this.authInterceptor)
			.addPathPatterns("/api/v1/widget/**", "/api/v1/agent/**", "/api/v1/admin/**");
	}

	@Override
	public void addArgumentResolvers(java.util.List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(new CallerArgumentResolver());
	}

	private static final class CallerArgumentResolver implements HandlerMethodArgumentResolver {

		@Override
		public boolean supportsParameter(MethodParameter parameter) {
			return Caller.class.equals(parameter.getParameterType());
		}

		@Override
		public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
				NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
			HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
			if (request == null) {
				throw new IllegalStateException("no servlet request available for the caller argument");
			}
			return AuthInterceptor.caller(request);
		}

	}

}
