package com.workmark.auth.service;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile({"prod", "staging"})
public class AccountTokenOutboxWorker {
    private final PostgresAccountTokenOutbox outbox;

    public AccountTokenOutboxWorker(PostgresAccountTokenOutbox outbox) {
        this.outbox = outbox;
    }

    @Scheduled(fixedDelayString = "${app.auth.outbox.poll-interval-ms:10000}")
    public void deliverPendingMessages() {
        outbox.processPendingBatch();
    }
}
