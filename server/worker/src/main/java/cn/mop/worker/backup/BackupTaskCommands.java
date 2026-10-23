package cn.mop.worker.backup;

import cn.mop.domain.DomainException;
import cn.mop.persistence.PostgresBackupArchiveRepository.Access;
import cn.mop.persistence.PostgresBackupArchiveRepository.Dispatch;
import cn.mop.persistence.PostgresBackupArchiveRepository.Phase;
import cn.mop.persistence.PostgresBackupTaskDiscovery.Task;
import cn.mop.persistence.PostgresBackupVersionRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Stable v1 command identities from the durable original submission, never from polling time or process ID. */
public final class BackupTaskCommands {
    private static final String OWNER = "backup-postprocessing-v1";

    private BackupTaskCommands() {}

    /** References must resolve to independent trusted evidence; their shape or hash does not prove success. */
    public record RegistrationProofs(String objectReference, String executionReference) {
        public RegistrationProofs {
            if (!reference(objectReference) || !reference(executionReference)) {
                throw invalid();
            }
        }
    }

    public static Dispatch dispatch(String binding, Access access, Task task) {
        return new Dispatch(identity(binding, access, task, "dispatch"), OWNER);
    }

    public static BackupArchiveDispatch.Command archive(String binding, Access access, Task task, String proofReference) {
        Dispatch dispatch = requireOriginalArchive(binding, access, task);
        return new BackupArchiveDispatch.Command(access, task.scope(), 0, dispatch,
                identity(binding, access, task, "start"), identity(binding, access, task, "unconfirmed"),
                identity(binding, access, task, "verify"), proofReference, trace(task));
    }

    static Dispatch requireOriginalArchive(String binding, Access access, Task task) {
        validate(binding, access, task);
        // A manual no-dispatch resolution is not permission for this scheduler to start another PUT.
        if (task.phase() == Phase.VERIFIED || task.phase() == Phase.STAGED && task.archiveEventVersion() != 0) {
            throw conflict();
        }
        Dispatch dispatch = dispatch(binding, access, task);
        if (task.dispatchId() != null && (!dispatch.id().equals(task.dispatchId())
                || !dispatch.owner().equals(task.dispatchOwner()))) {
            throw conflict();
        }
        return dispatch;
    }

    public static BackupVersionRegistrationDispatch.Command registration(String binding, Access access,
            Task task, RegistrationProofs proofs) {
        validate(binding, access, task);
        if (task.phase() != Phase.VERIFIED || task.archiveEventVersion() < 1 || proofs == null) {
            throw conflict();
        }
        var versionAccess = new PostgresBackupVersionRepository.Access(access.tenantId(), access.actorId(), access.permissionVersion());
        return new BackupVersionRegistrationDispatch.Command(versionAccess, task.scope(),
                identity(binding, access, task, "register"), task.stagedRunVersion(), task.archiveEventVersion(),
                task.originalLease(), proofs.objectReference(), proofs.executionReference(), trace(task));
    }

    private static UUID identity(String binding, Access access, Task task, String purpose) {
        validate(binding, access, task);
        var scope = task.scope();
        String canonical = String.join("\n", "MOP_BACKUP_POSTPROCESSING_V1", binding, access.actorId().toString(),
                scope.tenantId().toString(), scope.assetId().toString(), scope.runId().toString(),
                Integer.toString(scope.attemptNumber()), scope.versionId().toString(), task.submissionId().toString(), purpose);
        return UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static String trace(Task task) {
        return "backup-postprocessing:" + task.submissionId();
    }

    private static void validate(String binding, Access access, Task task) {
        if (binding == null || !binding.matches("[A-Za-z0-9._-]{1,128}") || access == null || task == null) {
            throw invalid();
        }
        if (!access.tenantId().equals(task.scope().tenantId())) {
            throw new DomainException(404, "BACKUP_TASK_NOT_FOUND", "当前范围没有备份任务");
        }
    }

    private static boolean reference(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }

    private static DomainException invalid() {
        return new DomainException(400, "INVALID_BACKUP_TASK_COMMAND", "备份任务原命令或证明引用无效");
    }

    private static DomainException conflict() {
        return new DomainException(409, "BACKUP_TASK_MANUAL_RECONCILIATION_REQUIRED", "原任务状态或派发身份不匹配，需要人工核对");
    }
}
