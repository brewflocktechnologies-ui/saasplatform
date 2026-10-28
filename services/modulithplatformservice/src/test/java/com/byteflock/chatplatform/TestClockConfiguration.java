package com.byteflock.chatplatform;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the production {@code Clock} bean in integration tests with a controllable {@link TestClock}. */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

	@Bean
	@Primary
	public TestClock testClock() {
		return new TestClock();
	}

}
