package com.arthvritt.platform;

import org.springframework.boot.SpringApplication;

public class TestPlatformBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(PlatformBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
