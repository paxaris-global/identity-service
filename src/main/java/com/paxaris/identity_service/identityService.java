	package com.paxaris.identity_service;

	import com.paxaris.identity_service.dto.KeycloakConfig;
	import org.springframework.boot.SpringApplication;
	import org.springframework.boot.autoconfigure.SpringBootApplication;
	import org.springframework.boot.context.properties.EnableConfigurationProperties;

	@SpringBootApplication
	@EnableConfigurationProperties(KeycloakConfig.class)
		public class identityService {

		public static void main(String[] args) {
			SpringApplication.run(identityService.class, args);
		}

	}
