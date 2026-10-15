package de.krasosu.fuzzydb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FuzzyDbApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(FuzzyDbApplication.class, args)));
    }
}
