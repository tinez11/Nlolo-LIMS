package tz.co.nlolo.lifeplatform.omnichannel.application;

/**
 * What a visitor agrees to before applying through the public quote link (2026-11-02, the user: "yes" -- Claude drafts
 * the wording, their compliance team reviews it, including licensing under the TIRA Insurance Digital Platforms
 * Guidelines 2022).
 *
 * <p><b>DRAFT, not yet reviewed by compliance.</b> Change the text by changing {@link #VERSION} with it: every
 * application records the version and the exact text shown, so what someone agreed to never changes underneath them.
 */
public final class PublicConsent {

    /** Bump with any change to either text. */
    public static final String VERSION = "PUBLIC-APPLY-2026-11-DRAFT-1";

    public static final String EN = String.join("\n",
        "Before you send this application, please confirm:",
        "1. The answers I have given, including my health answers, are true and complete to the best of my knowledge."
            + " I understand that a wrong or missing answer may mean a claim is not paid or the policy is cancelled.",
        "2. This is an application, not cover. Cover starts only if the insurer accepts it and the first premium is paid."
            + " The price I saw is an estimate; the insurer may change it, add conditions or decline.",
        "3. I agree that the insurer may keep and use the details I have given, and contact me by SMS and phone, to"
            + " assess this application, to check who I am, and to manage any policy that follows. The insurer keeps"
            + " these details as the law requires and does not sell them.",
        "4. I may be asked for my identity document before any policy is issued.");

    public static final String SW = String.join("\n",
        "Kabla ya kutuma ombi hili, tafadhali thibitisha:",
        "1. Majibu niliyotoa, pamoja na majibu ya afya, ni ya kweli na kamili kwa kadri ninavyojua. Ninaelewa kwamba"
            + " jibu lisilo sahihi au lililoachwa linaweza kusababisha dai lisilipwe au bima ifutwe.",
        "2. Hili ni ombi, si bima. Bima inaanza tu kampuni ikilikubali na malipo ya kwanza yakifanyika. Bei niliyoona ni"
            + " makadirio; kampuni inaweza kuibadilisha, kuweka masharti au kulikataa.",
        "3. Ninakubali kampuni ihifadhi na itumie taarifa nilizotoa, na iwasiliane nami kwa SMS na simu, ili kutathmini"
            + " ombi hili, kuthibitisha mimi ni nani, na kusimamia bima itakayofuata. Kampuni inahifadhi taarifa hizi kama"
            + " sheria inavyotaka na haiziuzi.",
        "4. Ninaweza kuombwa kitambulisho changu kabla bima haijatolewa.");

    private PublicConsent() {}

    public static String text(boolean swahili) {
        return swahili ? SW : EN;
    }
}
