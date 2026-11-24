package com.workmark.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;

@Component
@Profile({"prod", "staging"})
public class SmtpAccountTokenDelivery implements AccountTokenDelivery {
    private static final Logger log = LoggerFactory.getLogger(SmtpAccountTokenDelivery.class);
    private final JavaMailSender mailSender;
    private final String from;

    public SmtpAccountTokenDelivery(JavaMailSender mailSender,
                                    @Value("${app.mail.from}") String from,
                                    @Value("${spring.mail.host}") String host,
                                    @Value("${spring.mail.properties.mail.smtp.starttls.required:true}") boolean startTlsRequired,
                                    @Value("${spring.mail.properties.mail.smtp.ssl.checkserveridentity:true}") boolean certificateIdentityRequired) {
        if (from.isBlank()) {
            throw new IllegalStateException("A verified sender address is required in production");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("SMTP_HOST is required in production");
        }
        if (!startTlsRequired) {
            throw new IllegalStateException("SMTP STARTTLS must be required in production");
        }
        if (!certificateIdentityRequired) {
            throw new IllegalStateException("SMTP certificate hostname verification must be enabled");
        }
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public String deliver(String recipient, String displayName, String token, Instant expiresAt, Purpose purpose) {
        String action = purpose == Purpose.PASSWORD_SETUP ? "set up your WorkMark password" : "reset your WorkMark password";
        String endpoint = purpose == Purpose.PASSWORD_SETUP
                ? "POST /api/v1/auth/activate" : "POST /api/v1/auth/password-reset/confirm";
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject(purpose == Purpose.PASSWORD_SETUP
                ? "Complete your WorkMark account setup" : "Reset your WorkMark password");
        message.setText("Hello " + displayName + ",\n\nUse this one-time token to " + action + ":\n"
                + token + "\n\nSubmit it to " + endpoint + " before " + expiresAt
                + ". If you did not request this, you can ignore this message.");
        try {
            mailSender.send(message);
            return null;
        } catch (MailException exception) {
            log.error("Secure SMTP delivery failed for account purpose {}", purpose);
            throw exception;
        }
    }
}
