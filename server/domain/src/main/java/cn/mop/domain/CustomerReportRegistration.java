package cn.mop.domain;

/** Internal transaction result. created is never exposed in the Accepted API response. */
public record CustomerReportRegistration(String operationId, String ticketId, boolean created, boolean attachmentsChanged) {
    public CustomerReportRegistration(String operationId,String ticketId,boolean created){this(operationId,ticketId,created,false);}
}
