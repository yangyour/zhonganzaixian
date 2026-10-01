package cn.mop.persistence.configuration;

import org.junit.jupiter.api.Test;

/** Pure contract tests. These do not validate customer documents, runtime publication or PostgreSQL. */
class ConfigurationResolutionTest {
    @Test void missingAndPendingPreserveQuestions() { ConfigurationResolutionChecks.missingAndPendingPreserveQuestions(); }
    @Test void tenantIsolationPrecedesDiagnostics() { ConfigurationResolutionChecks.tenantIsolationPrecedesDiagnostics(); }
    @Test void overridesRequireAnExplicitLayer() { ConfigurationResolutionChecks.overridesRequireAnExplicitLayer(); }
    @Test void suppliedPrecedenceControlsResolution() { ConfigurationResolutionChecks.suppliedPrecedenceControlsResolution(); }
    @Test void pendingOverridesNeverFallBack() { ConfigurationResolutionChecks.pendingOverridesNeverFallBack(); }
    @Test void activeConflictsNeverChooseTheNewestOrFirst() { ConfigurationResolutionChecks.activeConflictsNeverChooseTheNewestOrFirst(); }
    @Test void timeBoundariesSelectDisjointVersions() { ConfigurationResolutionChecks.timeBoundariesSelectDisjointVersions(); }
    @Test void futureExpiredAndUndatedOverridesBlock() { ConfigurationResolutionChecks.futureExpiredAndUndatedOverridesBlock(); }
    @Test void internalApprovalAndRegistryValuesStayPending() { ConfigurationResolutionChecks.internalApprovalAndRegistryValuesStayPending(); }
    @Test void customerEvidenceMustBeVerifiedAndBound() { ConfigurationResolutionChecks.customerEvidenceMustBeVerifiedAndBound(); }
    @Test void publicationMustBeAuthorizedBoundAndDated() { ConfigurationResolutionChecks.publicationMustBeAuthorizedBoundAndDated(); }
    @Test void productionApprovalCannotDefaultToTrue() { ConfigurationResolutionChecks.productionApprovalCannotDefaultToTrue(); }
    @Test void nullZeroFalseAndDeclaredRangesRemainDistinct() { ConfigurationResolutionChecks.nullZeroFalseAndDeclaredRangesRemainDistinct(); }
    @Test void diagnosticAndCreateTimeSnapshotsAreDetached() { ConfigurationResolutionChecks.diagnosticAndCreateTimeSnapshotsAreDetached(); }
    @Test void sensitiveAndMalformedValuesFailClosed() { ConfigurationResolutionChecks.sensitiveAndMalformedValuesFailClosed(); }
}
