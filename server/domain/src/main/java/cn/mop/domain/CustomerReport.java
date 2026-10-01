package cn.mop.domain;

/** Validated customer-entered information. The reporter and tenant always come from the session. */
public record CustomerReport(String title, String description, String category, String impactScope,
                             String contactName, String contactPhone, String urgency, String assetId, java.util.List<String> attachmentIds) {
    public CustomerReport { attachmentIds=java.util.List.copyOf(attachmentIds); }
    public CustomerReport(String title,String description,String category,String impactScope,String contactName,String contactPhone,String urgency,String assetId){this(title,description,category,impactScope,contactName,contactPhone,urgency,assetId,java.util.List.of());}
}
