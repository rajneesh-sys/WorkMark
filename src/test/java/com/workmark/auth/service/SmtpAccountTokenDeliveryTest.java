package com.workmark.auth.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmtpAccountTokenDeliveryTest {
    @Test
    void refusesToStartWithoutRequiredStartTlsAndHostnameVerification() {
        JavaMailSender sender = mock(JavaMailSender.class);

        assertThrows(IllegalStateException.class, () -> new SmtpAccountTokenDelivery(
                sender, "no-reply@example.com", "smtp.example.com", false, true, true));
        assertThrows(IllegalStateException.class, () -> new SmtpAccountTokenDelivery(
                sender, "no-reply@example.com", "smtp.example.com", true, false, true));
        assertThrows(IllegalStateException.class, () -> new SmtpAccountTokenDelivery(
                sender, "no-reply@example.com", "smtp.example.com", true, true, false));
    }

    @Test
    void sendsSetupTokenWithStableOutboxMessageId() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        SmtpAccountTokenDelivery delivery = new SmtpAccountTokenDelivery(
                sender, "no-reply@example.com", "smtp.example.com", true, true, true);

        delivery.deliver("person@example.com", "Example Person", "one-time-secret",
                Instant.parse("2026-11-24T12:00:00Z"), AccountTokenDelivery.Purpose.PASSWORD_SETUP,
                "stable-outbox-id");

        verify(sender).send(message);
        assertEquals("<stable-outbox-id@workmark>", message.getHeader("Message-ID", null));
        assertTrue(message.getContent().toString().contains("one-time-secret"));
    }
}
