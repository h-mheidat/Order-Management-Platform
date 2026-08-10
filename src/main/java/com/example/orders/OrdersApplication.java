package com.example.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// Required by the outbox publisher. Without it the @Scheduled method is never invoked, orders commit
// their events, and nothing is ever published - with no error anywhere to indicate why.
@EnableScheduling
public class OrdersApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrdersApplication.class, args);
    }
}
