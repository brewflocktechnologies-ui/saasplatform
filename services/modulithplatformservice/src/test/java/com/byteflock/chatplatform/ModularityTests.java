package com.byteflock.chatplatform;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

	@Test
	void moduleBoundariesAreRespected() {
		ApplicationModules.of(ModulithplatformserviceApplication.class).verify();
	}

}
