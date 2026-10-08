package com.example.ecommerce.security;

import com.example.ecommerce.model.User;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Properties;

public final class EmailCodeService {
    private static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
    private static final int MAX_ATTEMPTS = 5;
    private static final int MAX_CHALLENGES = 1_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    @FunctionalInterface
    public interface VerificationMailer {
        void send(String email, String name, String code) throws MessagingException;
    }

    private record Challenge(long userId, String codeHash, Instant expiresAt, Instant sentAt, int attempts) {
    }

    private final Map<String, Challenge> challenges = new HashMap<>();
    private final VerificationMailer mailer;

    public EmailCodeService() {
        this(EmailCodeService::sendGmail);
    }

    public EmailCodeService(VerificationMailer mailer) {
        this.mailer = mailer;
    }

    public synchronized String sendCode(User user) {
        if (user == null || user.getId() == null || user.getEmail() == null) {
            throw new IllegalArgumentException("A conta para verificacao de email e invalida.");
        }

        Instant now = Instant.now();
        challenges.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        if (challenges.values().stream().anyMatch(challenge -> challenge.userId() == user.getId()
                && challenge.sentAt().plusSeconds(60).isAfter(now))) {
            throw new DeliveryException("Aguarde um minuto antes de solicitar outro codigo.");
        }
        challenges.entrySet().removeIf(entry -> entry.getValue().userId() == user.getId());
        if (challenges.size() >= MAX_CHALLENGES) {
            throw new DeliveryException("Nao foi possivel iniciar a verificacao agora. Tente novamente.");
        }

        String challengeId = randomToken();
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        try {
            mailer.send(user.getEmail(), user.getUsername(), code);
        } catch (MessagingException | IllegalStateException exception) {
            throw new DeliveryException(
                    "Nao foi possivel enviar o codigo. Verifique a configuracao de email da loja.", exception);
        }
        challenges.put(challengeId, new Challenge(
                user.getId(), hash(challengeId, code), now.plus(CODE_LIFETIME), now, 0));
        return challengeId;
    }

    public synchronized OptionalLong verify(String challengeId, String code) {
        Challenge challenge = challenges.get(challengeId);
        if (challenge == null) {
            return OptionalLong.empty();
        }
        if (!challenge.expiresAt().isAfter(Instant.now()) || challenge.attempts() >= MAX_ATTEMPTS) {
            challenges.remove(challengeId);
            return OptionalLong.empty();
        }
        if (code == null || !code.matches("\\d{6}")
                || !MessageDigest.isEqual(
                        hash(challengeId, code).getBytes(StandardCharsets.US_ASCII),
                        challenge.codeHash().getBytes(StandardCharsets.US_ASCII))) {
            if (challenge.attempts() + 1 >= MAX_ATTEMPTS) {
                challenges.remove(challengeId);
            } else {
                challenges.put(challengeId, new Challenge(
                        challenge.userId(), challenge.codeHash(), challenge.expiresAt(),
                        challenge.sentAt(), challenge.attempts() + 1));
            }
            return OptionalLong.empty();
        }
        challenges.remove(challengeId);
        return OptionalLong.of(challenge.userId());
    }

    private static void sendGmail(String email, String name, String code) throws MessagingException {
        String username = System.getenv("ECOMMERCE_SMTP_USERNAME");
        String appPassword = System.getenv("ECOMMERCE_SMTP_APP_PASSWORD");
        String from = System.getenv().getOrDefault("ECOMMERCE_SMTP_FROM", username == null ? "" : username);
        if (username == null || username.isBlank() || appPassword == null || appPassword.isBlank()
                || from.isBlank()) {
            throw new IllegalStateException("Gmail SMTP is not configured.");
        }

        Properties properties = new Properties();
        properties.put("mail.smtp.host", "smtp.gmail.com");
        properties.put("mail.smtp.port", "587");
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.starttls.enable", "true");
        properties.put("mail.smtp.starttls.required", "true");
        properties.put("mail.smtp.connectiontimeout", "10000");
        properties.put("mail.smtp.timeout", "10000");
        properties.put("mail.smtp.writetimeout", "10000");
        Session session = Session.getInstance(properties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, appPassword.replace(" ", ""));
            }
        });

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(email, true));
        message.setSubject("Codigo de verificacao - Fabregas Variedades", StandardCharsets.UTF_8.name());
        message.setText(
                "Ola, " + name + "!\n\nSeu codigo de verificacao e: " + code
                        + "\n\nEle expira em 10 minutos. Se voce nao solicitou este codigo, ignore esta mensagem.",
                StandardCharsets.UTF_8.name());
        Transport.send(message);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String challengeId, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(
                    digest.digest((challengeId + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public static final class DeliveryException extends RuntimeException {
        public DeliveryException(String message) {
            super(message);
        }

        public DeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
