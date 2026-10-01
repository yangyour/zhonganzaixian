package cn.mop.domain;

/** Validated customer-entered information. The reporter and tenant always come from the session. */
public record CustomerReport(String title, String description, String category, String impactScope,
                             String contactName, String contactPhone, String urgency, String assetId) {}
