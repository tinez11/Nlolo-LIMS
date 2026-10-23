package tz.co.nlolo.lifeplatform.dashboard.api;

/** How many of something in one month ("YYYY-MM"). Every month of a ranged series is present; zero means none. */
public record CountPoint(String month, long count) {}
