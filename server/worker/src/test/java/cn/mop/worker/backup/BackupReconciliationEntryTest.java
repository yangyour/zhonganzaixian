package cn.mop.worker.backup;
import cn.mop.adapters.backup.*;
import cn.mop.domain.DomainException;
import cn.mop.worker.WorkerApplication;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Entrypoint and denied writer tests only; never starts worker/network/DB. */
class BackupReconciliationEntryTest {
 @Test void explicitCommandsSelectRestrictedModeAndCannotFallBack(){try(var main=mockStatic(BackupWorkerMain.class)){
  WorkerApplication.main(new String[]{"--backup-reconcile-only"});main.verify(()->BackupWorkerMain.run(false,BackupTaskScheduler.Mode.RECONCILE_ONLY));
  WorkerApplication.main(new String[]{"--backup-reconcile-only-check"});main.verify(()->BackupWorkerMain.run(true,BackupTaskScheduler.Mode.RECONCILE_ONLY));
  main.verifyNoMoreInteractions();
 }}
 @Test void unknownOrMixedBackupArgumentsNeverStartAnyMode(){try(var main=mockStatic(BackupWorkerMain.class)){
  for(var args:new String[][]{{"--backup-reconcile-only","--backup-postprocess"},{"--backup-reconcile-only","--enable-upload"},{"--backup-reconcile"}})assertThrows(IllegalArgumentException.class,()->WorkerApplication.main(args));main.verifyNoInteractions();
 }}
 @Test void restrictedObjectPortForwardsReadsButDeniesAnyPutBeforeProvider(){var original=mock(BackupEnvelopeArchive.ObjectStore.class);var scoped=BackupWorkerMain.readOnlyObjects(original);var scope=new BackupEnvelopeEncryption.Scope(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),1,java.util.UUID.randomUUID());when(original.ready()).thenReturn(true);when(original.read(scope,3,"a".repeat(64))).thenReturn(new byte[]{1,2,3});assertTrue(scoped.ready());assertArrayEquals(new byte[]{1,2,3},scoped.read(scope,3,"a".repeat(64)));assertEquals(403,assertThrows(DomainException.class,()->scoped.storeImmutable(null)).status);verify(original,never()).storeImmutable(any());}

 @Test void restrictedFactoryCannotStartUploadEvenIfCallerInvokesLegacyOnce(){
  var fixture=new BackupArchiveDispatchTest();fixture.setup();var repository=mock(cn.mop.persistence.PostgresBackupArchiveRepository.class);
  when(repository.current(fixture.access,fixture.scope)).thenReturn(java.util.Optional.of(fixture.journal.current));
  var consumer=BackupArchiveDispatch.reconciliationOnly(fixture.enabled,(binding,access,scope,step)->{},repository);
  assertEquals(403,assertThrows(DomainException.class,()->consumer.once(fixture.command)).status);
  verify(repository).current(fixture.access,fixture.scope);verifyNoMoreInteractions(repository);
 }
}
