package tz.co.nlolo.lifeplatform.iam.api;

/**
 * Texting a staff member (2026-11-06: an administrator's password reset goes by SMS). A port iam declares and the
 * communication module implements, so iam -- which approvals and, through it, party and communication already depend
 * on -- never depends on them back.
 */
public interface StaffTexts {

    /**
     * Sends one SMS now. The body may hold a one-time password, so the implementation keeps no copy of it.
     *
     * @return null when the gateway accepted it, otherwise why it did not
     */
    String send(String phoneNumber, String body);
}
