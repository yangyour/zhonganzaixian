package cn.mop.persistence;

import cn.mop.domain.DomainException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AttachmentSecuritySchemaGuardTest {
    @Test void queueFailsClosedWhenProductionSchemaGuardIsUnavailable() throws Exception {
        var platform=mock(PostgresPlatformRepository.class);
        var connection=mock(Connection.class);
        doAnswer(call -> ((Function<Connection,?>)call.getArgument(0)).apply(connection)).when(platform).identityTransaction(any());
        try(var verifier=mockStatic(AttachmentSecuritySchemaVerifier.class)) {
            verifier.when(()->AttachmentSecuritySchemaVerifier.verify(connection)).thenThrow(new SQLException("synthetic drift"));
            var store=new PostgresAttachmentSecurityRepository(platform,"public","NOT_CONFIGURED");
            var error=assertThrows(DomainException.class,()->store.nextQueued("79000000-0000-4000-8000-000000000002"));
            assertEquals("ATTACHMENT_SECURITY_UNAVAILABLE",error.code);
            verifier.verify(()->AttachmentSecuritySchemaVerifier.verify(connection));
            verifyNoInteractions(connection);
        }
    }
    @Test void candidateBytesArePinnedButDoNotApplyTheMigration() throws Exception {
        AttachmentSecuritySchemaVerifier.resource();
        assertTrue(new String(AttachmentSecuritySchemaVerifier.sql(),java.nio.charset.StandardCharsets.UTF_8).contains("CREATE TRIGGER mop_attachment_security_guard"));
    }
}
