package com.restaurant.labor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LaborServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(LaborServiceApplication.class, args);
    }
}
