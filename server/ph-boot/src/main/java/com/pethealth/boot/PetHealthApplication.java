package com.pethealth.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.pethealth")
public class PetHealthApplication {

    public static void main(String[] args) {
        SpringApplication.run(PetHealthApplication.class, args);
    }
}
