package tz.co.nlolo.lifeplatform.communication;

/**
 * The aggregator's real answers, in one place.
 *
 * <p>Transcribed from NextSMS's published Postman collection rather than invented. The first
 * version of these tests stubbed a made-up `{"status":"ACCEPTED"}`, and every adapter written
 * against it passed while being wrong in three separate ways — so the point of a single shared
 * constant is that when the real contract is checked again, it is checked once.
 *
 * <p><b>PENDING is success.</b> NextSMS answers {@code groupName: "PENDING"} /
 * {@code name: "PENDING_ENROUTE"} when it accepts a message and queues it for delivery.
 */
final class NextSmsStubs {

    /** A message the aggregator has accepted. */
    static final String NEXTSMS_ACCEPTED = """
        {"messages":[{"to":"255700000000","status":{"groupId":1,"groupName":"PENDING","id":7,
        "name":"PENDING_ENROUTE","description":"Message sent to next instance"},"smsCount":1}]}""";

    private NextSmsStubs() {
    }
}
