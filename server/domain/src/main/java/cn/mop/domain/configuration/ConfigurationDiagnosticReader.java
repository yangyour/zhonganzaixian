package cn.mop.domain.configuration;

/** Reads metadata from an authoritative store; no unavailable source may become an empty successful fallback. */
public interface ConfigurationDiagnosticReader {
    /**
     * The calling API supplies identities/version from its already verified session. Format validation
     * here is not authorization: every implementation must recheck the current store membership.
     */
    record TrustedContext(String actorId,String tenantId,long expectedPermissionVersion) {
        public TrustedContext {
            ConfigurationDiagnostic.requireUuid(actorId);ConfigurationDiagnostic.requireUuid(tenantId);
            if(expectedPermissionVersion<1)throw new IllegalArgumentException("A current permission version is required");
        }
    }
    ConfigurationDiagnostic read(TrustedContext context,String key);
}
