package cn.mop.worker.backup;

import cn.mop.domain.DomainException;
import cn.mop.domain.backup.BackupArchivePayload.Scope;
import cn.mop.persistence.PostgresBackupArchiveRepository.Access;
import cn.mop.persistence.PostgresBackupArchiveRepository.Phase;
import cn.mop.persistence.PostgresBackupRunRepository.LeaseClaim;
import cn.mop.persistence.PostgresBackupTaskDiscovery.AssetScope;
import cn.mop.persistence.PostgresBackupTaskDiscovery.Cursor;
import cn.mop.persistence.PostgresBackupTaskDiscovery.Page;
import cn.mop.persistence.PostgresBackupTaskDiscovery.Task;
import cn.mop.worker.backup.BackupTaskCommands.RegistrationProofs;
import cn.mop.worker.backup.BackupTaskSchedule.Target;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BackupTaskSchedulerTest {
    final UUID tenant = UUID.randomUUID();
    final UUID asset = UUID.randomUUID();
    final Access access = new Access(tenant, UUID.randomUUID(), 7);
    final AssetScope assetScope = new AssetScope(tenant, asset);
    final Scope scope = new Scope(tenant, asset, UUID.randomUUID(), 1, UUID.randomUUID());
    final UUID submission = UUID.randomUUID();
    final Instant at = Instant.parse("2026-10-23T12:00:00Z");
    final LeaseClaim lease = new LeaseClaim(UUID.randomUUID(), "synthetic-lease-owner", 9);
    final String binding = "synthetic-postprocessing";
    final String proof = "sha256:" + "a".repeat(64);
    final AtomicInteger proofReads = new AtomicInteger();
    final List<BackupArchiveDispatch.Command> archiveCommands = new ArrayList<>();
    final List<BackupVersionRegistrationDispatch.Command> registrationCommands = new ArrayList<>();
    final BackupTaskScheduler.ProofSource proofs = new BackupTaskScheduler.ProofSource() {
        public String archiveReference(Task original) {
            proofReads.incrementAndGet();
            return proof;
        }

        public RegistrationProofs registrationReferences(Task original) {
            proofReads.incrementAndGet();
            return new RegistrationProofs(proof, "sha256:" + "b".repeat(64));
        }
    };

    @Test
    void disabledTickAndLifecycleHaveNoIo() throws Exception {
        var scheduler = new BackupTaskScheduler(BackupTaskSchedule.disabled(), (a, s, c, n) -> {
            fail("Disabled scheduler must not discover");
            return null;
        }, proofs, this::archive, this::registration);
        assertFalse(scheduler.tick().enabled());
        var observed = new ArrayList<BackupTaskScheduler.Cycle>();
        scheduler.run(() -> false, observed::add);
        assertEquals(1, observed.size());
        assertFalse(observed.get(0).noProcessingErrors());
        assertEquals(0, proofReads.get());
    }

    @Test
    void originalIdsSurviveReconstructionAndPermissionVersionRefresh() {
        var task = task(Phase.STAGED, 0, false);
        var first = BackupTaskCommands.archive(binding, access, task, proof);
        var replay = BackupTaskCommands.archive(binding, new Access(tenant, access.actorId(), 8), task, proof);
        assertEquals(first.startCommand(), replay.startCommand());
        assertEquals(first.verifyCommand(), replay.verifyCommand());
        assertEquals(first.dispatch(), replay.dispatch());
        assertNotEquals(first.startCommand(), first.verifyCommand());
        assertNotEquals(first.startCommand(), first.unconfirmedCommand());
        assertEquals(0, replay.expectedArchiveVersion());
    }

    @Test
    void identityChangesWithBindingActorOrOriginalSubmission() {
        var task = task(Phase.STAGED, 0, false);
        var expected = BackupTaskCommands.dispatch(binding, access, task);
        assertNotEquals(expected, BackupTaskCommands.dispatch("other", access, task));
        assertNotEquals(expected, BackupTaskCommands.dispatch(binding, new Access(tenant, UUID.randomUUID(), 7), task));
        var other = new Task(scope, UUID.randomUUID(), 2, at, Phase.STAGED, 0, null, null, 0, lease);
        assertNotEquals(expected, BackupTaskCommands.dispatch(binding, access, other));
        assertEquals(404, assertThrows(DomainException.class, () -> BackupTaskCommands.dispatch(binding,
                new Access(UUID.randomUUID(), access.actorId(), 7), task)).status);
    }

    @Test
    void oneTickDoesNotGuessVerifiedVersionAndImmediatelyRegister() {
        var scheduler = scheduler(new Page(List.of(task(Phase.STAGED, 0, false)), null));
        var cycle = scheduler.tick();
        assertEquals(1, cycle.archiveRecorded());
        assertEquals(0, cycle.registrationsRecorded());
        assertEquals(1, archiveCommands.size());
        assertTrue(registrationCommands.isEmpty());
    }

    @Test
    void verifiedTaskUsesExactStagedRunVersionOriginalLeaseAndTerminalArchiveVersion() {
        var scheduler = scheduler(new Page(List.of(task(Phase.VERIFIED, 3, true)), null));
        assertEquals(1, scheduler.tick().registrationsRecorded());
        var command = registrationCommands.get(0);
        assertEquals(2, command.expectedRunVersion());
        assertEquals(3, command.expectedArchiveVersion());
        assertEquals(lease, command.lease());
        assertTrue(archiveCommands.isEmpty());
        assertEquals(command.commandId(), BackupTaskCommands.registration(binding, access,
                task(Phase.VERIFIED, 3, true), new RegistrationProofs(proof, "sha256:" + "b".repeat(64))).commandId());
    }

    @Test
    void manualRetryAndForeignDispatchAreBlockedBeforeProofLookup() {
        var manual = task(Phase.STAGED, 3, true);
        var foreign = new Task(scope, submission, 2, at, Phase.STORE_STARTED, 1,
                UUID.randomUUID(), "foreign-worker", 1, lease);
        var cycle = scheduler(new Page(List.of(manual, foreign), null)).tick();
        assertEquals(2, cycle.blocked());
        assertEquals(0, proofReads.get());
        assertTrue(archiveCommands.isEmpty());
        assertFalse(cycle.noProcessingErrors());
    }

    @Test
    void unknownResultAndThrownPostDispatchFailureNeverCountSuccess() {
        for (boolean throwsAfterCall : List.of(false, true)) {
            var scheduler = new BackupTaskScheduler(schedule(), (a, s, c, n) -> new Page(List.of(task(Phase.STAGED, 0, false)), null),
                    proofs, command -> {
                        if (throwsAfterCall) {
                            throw new DomainException(403, "REVOKED", "private detail");
                        }
                        return new BackupArchiveDispatch.Result(BackupArchiveDispatch.Status.RECONCILIATION_REQUIRED, null, true);
                    }, this::registration);
            var cycle = scheduler.tick();
            assertEquals(1, cycle.reconciliationRequired());
            assertEquals(0, cycle.archiveRecorded());
            assertFalse(cycle.toString().contains("private"));
        }
    }

    @Test
    void malformedConsumerSuccessIsStillUnknown() {
        var scheduler = new BackupTaskScheduler(schedule(), (a, s, c, n) -> new Page(List.of(task(Phase.STAGED, 0, false)), null),
                proofs, command -> new BackupArchiveDispatch.Result(BackupArchiveDispatch.Status.ARCHIVE_VERIFICATION_RECORDED, null, false),
                this::registration);
        assertEquals(1, scheduler.tick().reconciliationRequired());
    }

    @Test
    void missingProofDoesNotInvokeConsumers() {
        var unavailable = new BackupTaskScheduler.ProofSource() {
            public String archiveReference(Task task) { throw new IllegalStateException("private detail"); }
            public RegistrationProofs registrationReferences(Task task) { throw new IllegalStateException("private detail"); }
        };
        var scheduler = new BackupTaskScheduler(schedule(), (a, s, c, n) -> new Page(List.of(task(Phase.STAGED, 0, false)), null),
                unavailable, this::archive, this::registration);
        assertEquals(1, scheduler.tick().unavailable());
        assertTrue(archiveCommands.isEmpty());
    }

    @Test
    void failureOnFirstAssetDoesNotStarveSecondAndCursorWrapsAfterFullPass() {
        var second = new AssetScope(tenant, UUID.randomUUID());
        var config = new BackupTaskSchedule(true, binding, List.of(new Target(access, second), new Target(access, assetScope)), 1, 100);
        var expectedCursor = new Cursor(assetScope, at, scope.versionId());
        var calls = new ArrayList<Cursor>();
        var scheduler = new BackupTaskScheduler(config, (a, s, cursor, n) -> {
            assertEquals(1, n);
            if (s.equals(second)) {
                throw new IllegalStateException("source unavailable");
            }
            calls.add(cursor);
            return calls.size() == 1 ? new Page(List.of(task(Phase.STAGED, 0, false)), expectedCursor) : new Page(List.of(), null);
        }, proofs, this::archive, this::registration);
        assertEquals(1, scheduler.tick().discoveryFailures());
        scheduler.tick();
        scheduler.tick();
        assertNull(calls.get(0));
        assertEquals(expectedCursor, calls.get(1));
        assertNull(calls.get(2));
    }

    @Test
    void crossScopePageIsRejectedBeforeAnyProofOrDispatch() {
        var other = new Scope(tenant, UUID.randomUUID(), scope.runId(), 1, scope.versionId());
        var foreign = new Task(other, submission, 2, at, Phase.STAGED, 0, null, null, 0, lease);
        assertEquals(1, scheduler(new Page(List.of(foreign), null)).tick().discoveryFailures());
        assertEquals(0, proofReads.get());
    }

    @Test
    void oversizedPageIsRejectedRatherThanDroppingUndispatchedRows() {
        var items = new ArrayList<Task>();
        for (int i = 0; i < 11; i++) {
            items.add(task(Phase.STAGED, 0, false));
        }
        assertEquals(1, scheduler(new Page(items, null)).tick().discoveryFailures());
        assertTrue(archiveCommands.isEmpty());
    }

    @Test
    void lifecycleStopsAfterRequestedBoundedCycle() throws Exception {
        var stop = new AtomicBoolean();
        var cycles = new ArrayList<BackupTaskScheduler.Cycle>();
        scheduler(new Page(List.of(), null)).run(stop::get, cycle -> {
            cycles.add(cycle);
            stop.set(true);
        });
        assertEquals(1, cycles.size());
    }

    @Test
    void stopDuringPagePreventsAnotherDispatchAndKeepsOriginalCursor() throws Exception {
        var stop = new AtomicBoolean();
        var positions = new ArrayList<Cursor>();
        var page = new Page(List.of(task(Phase.STAGED, 0, false), task(Phase.STAGED, 0, false)),
                new Cursor(assetScope, at, scope.versionId()));
        var scheduler = new BackupTaskScheduler(schedule(), (a, s, cursor, n) -> {
            positions.add(cursor);
            return page;
        }, proofs, command -> {
            stop.set(true);
            return archive(command);
        }, this::registration);
        scheduler.run(stop::get, cycle -> assertEquals(1, cycle.discovered()));
        assertEquals(1, archiveCommands.size());
        scheduler.tick();
        assertNull(positions.get(1));
    }

    @Test
    void scheduleRejectsMissingBindingDuplicateScopesAndUnboundedPoll() {
        var targets = List.of(new Target(access, assetScope));
        assertThrows(IllegalArgumentException.class, () -> new BackupTaskSchedule(true, null, targets, 10, 1000));
        assertThrows(IllegalArgumentException.class, () -> new BackupTaskSchedule(true, binding, targets, 51, 1000));
        assertThrows(IllegalArgumentException.class, () -> new BackupTaskSchedule(true, binding, targets, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> new BackupTaskSchedule(true, binding,
                List.of(targets.get(0), targets.get(0)), 10, 1000));
    }

    @Test
    void realArchiveConsumerRecoversLostStartAcrossSchedulerRestartWithoutPut() {
        var fixture = new BackupArchiveDispatchTest();
        fixture.setup();
        fixture.journal.commitStartThenFail = true;
        var config = new BackupTaskSchedule(true, binding, List.of(new Target(fixture.access,
                new AssetScope(fixture.scope.tenantId(), fixture.scope.assetId()))), 1, 100);
        BackupTaskScheduler.Discovery discovery = (a, s, c, n) -> {
            var row = fixture.journal.current;
            var event = row.event();
            var original = new Task(fixture.scope, row.source().submissionId(), row.source().runVersion(),
                    row.source().recordedAt(), event.phase(), event.version(),
                    event.dispatch() == null ? null : event.dispatch().id(),
                    event.dispatch() == null ? null : event.dispatch().owner(), event.dispatchCount(), lease);
            return new Page(List.of(original), null);
        };
        var first = new BackupTaskScheduler(config, discovery, proofs, fixture.consumer()::once, this::registration);
        assertEquals(1, first.tick().reconciliationRequired());
        assertEquals(0, fixture.writes);
        fixture.journal.commitStartThenFail = false;
        var restarted = new BackupTaskScheduler(config, discovery, proofs, fixture.consumer()::once, this::registration);
        assertEquals(1, restarted.tick().archiveRecorded());
        assertEquals(0, fixture.writes);
        assertEquals(List.of("read", "start:0", "read", "verify:1"), fixture.actions);
    }

    @Test
    void realArchiveConsumerNeverRepeatsPutAfterLostObjectResponse() {
        var fixture = new BackupArchiveDispatchTest();
        fixture.setup();
        fixture.failWrite = true;
        var original = new Task(fixture.scope, fixture.source.submissionId(), 2, fixture.now,
                Phase.STAGED, 0, null, null, 0, lease);
        var command = BackupTaskCommands.archive(binding, fixture.access, original, proof);
        assertEquals(BackupArchiveDispatch.Status.RECONCILIATION_REQUIRED, fixture.consumer().once(command).status());
        var current = fixture.journal.current.event();
        var resumed = new Task(fixture.scope, fixture.source.submissionId(), 2, fixture.now,
                current.phase(), current.version(), current.dispatch().id(), current.dispatch().owner(), 1, lease);
        fixture.failWrite = false;
        var reconstructed = BackupTaskCommands.archive(binding, fixture.access, resumed, proof);
        assertEquals(command, reconstructed);
        assertEquals(BackupArchiveDispatch.Status.ARCHIVE_VERIFICATION_RECORDED, fixture.consumer().once(reconstructed).status());
        assertEquals(1, fixture.writes);
    }

    @Test
    void realRegistrationConsumerRecoversCommittedReplyWithSameCommandAfterRestart() {
        var original = task(Phase.VERIFIED, 3, true);
        var stored = new java.util.concurrent.atomic.AtomicReference<cn.mop.persistence.PostgresBackupVersionRepository.Receipt>();
        var writes = new AtomicInteger();
        var registry = new BackupVersionRegistrationDispatch.Registry() {
            public java.util.Optional<cn.mop.persistence.PostgresBackupVersionRepository.Receipt> current(
                    cn.mop.persistence.PostgresBackupVersionRepository.Access identity, Scope target) {
                return java.util.Optional.ofNullable(stored.get());
            }

            public cn.mop.persistence.PostgresBackupVersionRepository.Receipt register(BackupVersionRegistrationDispatch.Command command) {
                writes.incrementAndGet();
                var candidate = new cn.mop.persistence.PostgresBackupVersionRepository.Candidate(scope, 2, 3,
                        "c".repeat(64), 300, "kms-key-ref:synthetic", proof, at, lease);
                var row = new cn.mop.persistence.PostgresBackupVersionRepository.Registration(candidate, command.commandId(),
                        "d".repeat(64), command.objectRecheckReference(), command.executionReference(),
                        access.actorId(), at.plusMillis(1), 3);
                stored.set(new cn.mop.persistence.PostgresBackupVersionRepository.Receipt(row, false));
                throw new IllegalStateException("Synthetic lost commit reply");
            }
        };
        var consumer = new BackupVersionRegistrationDispatch(
                new BackupVersionRegistrationDispatch.Binding(true, "backup-version-dispatch:synthetic"),
                (b, a, s, step) -> {}, registry);
        BackupTaskScheduler.Discovery stalePage = (a, s, c, n) -> new Page(List.of(original), null);
        var first = new BackupTaskScheduler(schedule(), stalePage, proofs, this::archive, consumer::once);
        assertEquals(1, first.tick().reconciliationRequired());
        var restarted = new BackupTaskScheduler(schedule(), stalePage, proofs, this::archive, consumer::once);
        assertEquals(1, restarted.tick().registrationsRecorded());
        assertEquals(1, writes.get());
        assertTrue(archiveCommands.isEmpty());
    }


    @Test void reconcileModeSkipsStagedAndAlreadyVerifiedWithoutReadingProofOrRegistering(){
        var page=new Page(List.of(task(Phase.STAGED,0,false),task(Phase.VERIFIED,2,true)),null);
        var scheduler=new BackupTaskScheduler(schedule(),(a,s,c,n)->page,proofs,this::archive,this::registration,BackupTaskScheduler.Mode.RECONCILE_ONLY);
        var cycle=scheduler.tick();assertEquals(2,cycle.discovered());assertEquals(2,cycle.skippedByMode());assertEquals(0,cycle.archiveRecorded());assertEquals(0,cycle.registrationsRecorded());assertEquals(0,proofReads.get());assertTrue(archiveCommands.isEmpty());assertTrue(registrationCommands.isEmpty());
    }
    @Test void reconcileModeProcessesOnlyOriginalUncertainDispatches(){
        var page=new Page(List.of(task(Phase.STORE_STARTED,1,true),task(Phase.VERIFY_REQUIRED,2,true)),null);
        var scheduler=new BackupTaskScheduler(schedule(),(a,s,c,n)->page,proofs,this::archive,this::registration,BackupTaskScheduler.Mode.RECONCILE_ONLY);
        var cycle=scheduler.tick();assertEquals(2,cycle.archiveRecorded());assertEquals(0,cycle.registrationsRecorded());assertEquals(0,cycle.skippedByMode());assertEquals(2,archiveCommands.size());assertTrue(registrationCommands.isEmpty());assertEquals(archiveCommands.get(0).dispatch(),archiveCommands.get(1).dispatch());
    }
    @Test void reconcileModeDoesNotTrustFreshStagedStateAfterStaleDiscovery(){
        var fixture=new BackupArchiveDispatchTest();fixture.setup();
        var original=new Task(fixture.scope,fixture.source.submissionId(),fixture.source.runVersion(),fixture.now,Phase.STAGED,0, null,null,0,lease);
        var targetAccess=fixture.access;
        var dispatch=BackupTaskCommands.dispatch(binding,targetAccess,original);
        var stale=new Task(fixture.scope,original.submissionId(),original.stagedRunVersion(),original.stagedAt(),Phase.STORE_STARTED,1,dispatch.id(),dispatch.owner(),1,lease);
        var schedule=new BackupTaskSchedule(true,binding,List.of(new Target(targetAccess,new AssetScope(fixture.scope.tenantId(),fixture.scope.assetId()))),10,100);
        var scheduler=new BackupTaskScheduler(schedule,(a,s,c,n)->new Page(List.of(stale),null),proofs,fixture.consumer()::reconcileOnly,cmd->{fail("No registration");return null;},BackupTaskScheduler.Mode.RECONCILE_ONLY);
        assertEquals(1,scheduler.tick().reconciliationRequired());assertEquals(0,fixture.writes);assertEquals(List.of("read"),fixture.actions);
    }
    private BackupTaskSchedule schedule() {
        return new BackupTaskSchedule(true, binding, List.of(new Target(access, assetScope)), 10, 100);
    }

    private BackupTaskScheduler scheduler(Page page) {
        return new BackupTaskScheduler(schedule(), (a, s, c, n) -> page, proofs, this::archive, this::registration);
    }

    private Task task(Phase phase, long version, boolean dispatched) {
        var staged = new Task(scope, submission, 2, at, Phase.STAGED, 0, null, null, 0, lease);
        var dispatch = BackupTaskCommands.dispatch(binding, access, staged);
        return new Task(scope, submission, 2, at, phase, version, dispatched ? dispatch.id() : null,
                dispatched ? dispatch.owner() : null, dispatched ? 1 : 0, lease);
    }

    private BackupArchiveDispatch.Result archive(BackupArchiveDispatch.Command command) {
        archiveCommands.add(command);
        return new BackupArchiveDispatch.Result(BackupArchiveDispatch.Status.ARCHIVE_VERIFICATION_RECORDED, 2L, false);
    }

    private BackupVersionRegistrationDispatch.Result registration(BackupVersionRegistrationDispatch.Command command) {
        registrationCommands.add(command);
        return new BackupVersionRegistrationDispatch.Result(
                BackupVersionRegistrationDispatch.Status.REGISTRATION_AND_RUN_COMPLETION_RECORDED, 3L, true, false);
    }
}
