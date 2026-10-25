package com.byteflock.chatplatform.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/bootstrap")
class BootstrapController {

	private static final Logger log = LoggerFactory.getLogger(BootstrapController.class);

	private final BootstrapRepository repository;

	BootstrapController(BootstrapRepository repository) {
		this.repository = repository;
	}

	@GetMapping
	BootstrapRecord bootstrap() {
		// Request-scoped log line: carries trace_id/span_id, proving log<->trace correlation in Loki/Tempo.
		log.info("Bootstrap record requested");
		return this.repository.findLatest()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
					"No bootstrap record found. Run the Flyway migration."));
	}

}
