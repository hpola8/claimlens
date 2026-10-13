package com.claimlens;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ClaimLensApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClaimLensApplication.class, args);
    }
}
