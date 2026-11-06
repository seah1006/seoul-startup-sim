package com.seah.startupsim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StartupSimApplication {
    public static void main(String[] args) {
        SpringApplication.run(StartupSimApplication.class, args);
    }
}
