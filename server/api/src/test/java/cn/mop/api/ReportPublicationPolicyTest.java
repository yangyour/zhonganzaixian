package cn.mop.api;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ReportPublicationPolicyTest {
 String tenant="79000000-0000-4000-8000-000000000002";
 @Test void missingPolicyNeverGrantsDefaultReviewOrCustomerConfirmation(){var p=ReportPublicationConfiguration.policyProvider(null).apply(tenant);assertNull(p.reference());assertNull(p.reviewers());assertNull(p.publishers());assertNull(p.customerTemplateConfirmed());assertNull(p.annualEnabled());}
 @Test void differentTenantDoesNotInheritPolicy(){var p=ReportPublicationConfiguration.policyProvider("{\""+tenant+"\":{\"reference\":\"explicit\"}}");assertNotNull(p.apply(tenant).reference());assertNull(p.apply("79000000-0000-4000-8000-000000000003").reference());}
 @Test void sameLabelWithChangedPolicyChangesApprovalIdentity(){String prefix="{\""+tenant+"\":{\"reference\":\"v1\",\"self_review_allowed\":";var a=ReportPublicationConfiguration.policyProvider(prefix+"false}}");var b=ReportPublicationConfiguration.policyProvider(prefix+"true}}");assertNotEquals(a.apply(tenant).reference(),b.apply(tenant).reference());}
 @Test void customerConfirmedWithoutEvidenceAndUnknownFieldsAreRejected(){assertThrows(IllegalStateException.class,()->ReportPublicationConfiguration.policyProvider("{\""+tenant+"\":{\"customer_template_confirmed\":true}}"));assertThrows(IllegalStateException.class,()->ReportPublicationConfiguration.policyProvider("{\""+tenant+"\":{\"unknown\":true}}"));}
}
