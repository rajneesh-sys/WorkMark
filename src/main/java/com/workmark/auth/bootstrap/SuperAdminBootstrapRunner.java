package com.workmark.auth.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class SuperAdminBootstrapRunner implements ApplicationRunner {
    private final SuperAdminBootstrapService bootstrapService;

    public SuperAdminBootstrapRunner(SuperAdminBootstrapService bootstrapService) {
        this.bootstrapService = bootstrapService;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrapService.bootstrapIfEnabled();
    }
}
