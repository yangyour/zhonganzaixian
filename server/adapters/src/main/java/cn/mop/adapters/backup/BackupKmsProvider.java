package cn.mop.adapters.backup;

import java.util.function.Function;

/** Reviewed KMS integration loaded by exact ID. No plaintext-key file or synthetic provider ships in the application. */
public interface BackupKmsProvider {
    String id();

    Session open(Function<String, String> privateSettings);

    interface Session extends BackupEnvelopeEncryption.DataKeyProvider, AutoCloseable {
        /** Must probe the configured key service and approved key binding, not merely check that fields are nonempty. */
        boolean ready();

        @Override
        void close();
    }
}
