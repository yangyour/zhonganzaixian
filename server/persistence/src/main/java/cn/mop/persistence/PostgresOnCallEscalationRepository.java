package cn.mop.persistence;

import cn.mop.domain.*;
import cn.mop.domain.sla.*;
import cn.mop.domain.sla.OnCallEscalationRepository.*;
import cn.mop.domain.sla.SlaAvailabilityAndCompensation.*;
import cn.mop.domain.sla.SlaComplianceScenario.*;
import cn.mop.domain.ticket.CustomerConfirmationWindow.Tier;
import cn.mop.domain.ticket.TicketLifecycle;
import cn.mop.domain.ticket.TicketLifecycle.State;
import cn.mop.domain.time.IntervalSets.Interval;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.sql.Date;
import java.time.*;
import java.util.*;

/**
 * PostgreSQL repository for On-Call Board, Shift Roster, Append-Only Handoffs,
 * Configurable Escalation Policies/Step Runs, and SLA Monthly Results (V36: M5-9, M5-10, M5-12, M5-13, M5-19).
 */
public final class PostgresOnCallEscalationRepository implements OnCallEscalationRepository {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final DataSource source;
    private final PostgresPlatformRepository platform;

    private record TicketRow(String ticketId, Instant createdAt, TicketLifecycle.Snapshot snapshot) {}
    @FunctionalInterface private interface Work<T> { T run(Connection c) throws SQLException; }

    public PostgresOnCallEscalationRepository(DataSource source, PostgresPlatformRepository platform) {
        this.source = source;
        this.platform = platform;
    }

    private <T> T read(Access a, String capability, boolean internalOnly, Work<T> work) {
        try (Connection c = source.getConnection()) {
            c.setReadOnly(true);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            try {
                member(c, a, capability, internalOnly, false);
                T out = work.run(c);
                c.commit();
                return out;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    @Override
    public void authorizeWrite(Access a) {
        try {
            Connection c = platform.idempotentCommandConnection();
            member(c, a, "ticket_notes:write_internal", true, true);
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    @Override
    public OnCallBoardView board(Access a, int limit) {
        if (limit < 1 || limit > 100) throw invalid();
        return read(a, "tickets:read", true, c -> {
            Instant observed = now(c);
            Instant todayStart = ZonedDateTime.ofInstant(observed, SHANGHAI).toLocalDate().atStartOfDay(SHANGHAI).toInstant();
            List<EscalationPolicy> policies = loadPolicies(c, a.tenantId(), observed);
            Map<PolicyTier, EscalationPolicy> byTier = new EnumMap<>(PolicyTier.class);
            for (EscalationPolicy p : policies) byTier.put(p.tier(), p);

            List<TicketRow> ticketRows = loadTenantTickets(c, a.tenantId(), 500);
            int unack = 0, critical = 0, inProgress = 0, pendingReview = 0, recoveredToday = 0;
            List<Long> mttaSamples = new ArrayList<>();
            for (TicketRow row : ticketRows) {
                TicketLifecycle.Snapshot snap = row.snapshot();
                State st = snap.state();
                String prio = snap.sla().priority();
                if (st == State.OPEN) unack++;
                if ((st == State.OPEN || st == State.ACCEPTED || st == State.IN_PROGRESS) && ("P1".equals(prio) || "P2".equals(prio))) {
                    critical++;
                }
                if (st == State.ACCEPTED || st == State.IN_PROGRESS) inProgress++;
                if (st == State.RESOLVED) pendingReview++;
                if (snap.resolvedAt() != null && !snap.resolvedAt().isBefore(todayStart)) recoveredToday++;
                if (snap.acceptedAt() != null && !snap.acceptedAt().isBefore(snap.openedAt()) && mttaSamples.size() < 200) {
                    mttaSamples.add(Duration.between(snap.openedAt(), snap.acceptedAt()).toSeconds());
                }
            }
            LatencyStats mttaStats = SlaAvailabilityAndCompensation.computeLatency(mttaSamples, 0);
            BoardKpis kpis = new BoardKpis(unack, critical, inProgress, pendingReview, recoveredToday, mttaStats.p50Sec(), MTTA_TARGET_SEC);

            Map<String, Integer> latestStepByTicket = new HashMap<>();
            try (var s = statement(c, "SELECT DISTINCT ON (ticket_id) ticket_id::text, COALESCE(escalated_to_step, step_order) AS cur_step "
                + "FROM public.mop_escalation_step_run WHERE tenant_id=?::uuid ORDER BY ticket_id, recorded_at DESC, id DESC")) {
                s.setString(1, a.tenantId());
                try (var r = s.executeQuery()) {
                    while (r.next()) latestStepByTicket.put(r.getString("ticket_id"), r.getInt("cur_step"));
                }
            }

            List<QueueItem> queue = new ArrayList<>();
            for (TicketRow row : ticketRows) {
                if (queue.size() >= limit) break;
                TicketLifecycle.Snapshot snap = row.snapshot();
                State st = snap.state();
                if (st != State.OPEN && st != State.ACCEPTED && st != State.IN_PROGRESS && st != State.RESOLVED) continue;
                String ticketId = row.ticketId();
                String priority = snap.sla().priority();
                String tierRaw = snap.sla().tier().name();
                var eval = TicketSlaClockEvaluation.evaluate(snap, null, observed);
                var activeClock = snap.acceptedAt() == null ? eval.responseClock() : eval.recoveryClock();
                Long remainingSec = null;
                BigDecimal progressRatio = null;
                if (activeClock.dueAt() != null) {
                    Instant refNow = activeClock.completedAt() != null ? activeClock.completedAt() : observed;
                    remainingSec = Duration.between(refNow, activeClock.dueAt()).toSeconds();
                    long totalSec = Math.max(1L, Duration.between(snap.openedAt(), activeClock.dueAt()).toSeconds());
                    long elapsedSec = Math.max(0L, Duration.between(snap.openedAt(), refNow).toSeconds());
                    progressRatio = BigDecimal.valueOf(elapsedSec).divide(BigDecimal.valueOf(totalSec), 4, RoundingMode.HALF_UP);
                    if (progressRatio.compareTo(BigDecimal.ONE) > 0) progressRatio = new BigDecimal("1.0000");
                }
                boolean breached = Boolean.TRUE.equals(eval.responseClock().breached()) || Boolean.TRUE.equals(eval.recoveryClock().breached());
                PolicyTier pTier = PolicyTier.valueOf(tierRaw);
                EscalationPolicy policy = byTier.getOrDefault(pTier, byTier.get(PolicyTier.FALLBACK));
                int curStep = latestStepByTicket.getOrDefault(ticketId, 1);
                Integer nextStep = null;
                String nextRole = null;
                if (policy != null && curStep < policy.steps().size()) {
                    EscalationStep next = policy.steps().get(curStep);
                    nextStep = next.stepOrder();
                    nextRole = next.targetRole().name();
                }
                queue.add(new QueueItem(
                    ticketId, priority, st.name(), tierRaw, snap.assigneeId(), snap.openedAt(),
                    snap.sla().responseDueAt(), snap.sla().recoveryDueAt(),
                    remainingSec, progressRatio, breached, curStep, nextStep, nextRole
                ));
            }

            List<RosterEntry> rosters = loadRosters(c, a.tenantId(), limit);
            List<HandoffEntry> handoffs = loadHandoffs(c, a.tenantId(), limit);
            List<EscalationStepRun> runs = loadStepRuns(c, a.tenantId(), limit);
            return new OnCallBoardView(a.tenantId(), kpis, queue, rosters, handoffs, policies, runs, AUTO_REFRESH_SEC, HANDOFF_LOCAL_TIME, observed);
        });
    }

    @Override
    public RosterEntry upsertRoster(Access a, RosterCommand cmd) {
        if (cmd == null) throw invalid();
        Connection c = platform.idempotentCommandConnection();
        try {
            member(c, a, "ticket_notes:write_internal", true, true);
            requireInternalMember(c, a.tenantId(), cmd.primaryOncallUserId());
            if (cmd.backupOncallUserId() != null) requireInternalMember(c, a.tenantId(), cmd.backupOncallUserId());
            lock(c, "mop.oncall_roster|" + a.tenantId());
            Instant at = now(c);
            Optional<RosterEntry> existing = findRoster(c, a.tenantId(), cmd.shiftDate(), cmd.shiftCode());
            long currentVersion = existing.map(RosterEntry::version).orElse(0L);
            if (cmd.expectedVersion() != currentVersion)
                throw new DomainException(409, "VERSION_CONFLICT", "值班排班版本已变化，请刷新后重试");
            long nextVersion = currentVersion + 1;
            String id = existing.map(RosterEntry::id).orElseGet(() -> UUID.randomUUID().toString());
            if (existing.isEmpty()) {
                try (var s = statement(c, "INSERT INTO public.mop_oncall_roster(id,tenant_id,version,shift_date,shift_code,handoff_local_time,primary_oncall_user_id,backup_oncall_user_id,note,roster_policy_status,updated_by,updated_at) VALUES(?::uuid,?::uuid,?,?,?,'18:00',?::uuid,?::uuid,?,'CONFIG_PENDING_Q15',?::uuid,?)")) {
                    s.setString(1, id);
                    s.setString(2, a.tenantId());
                    s.setLong(3, nextVersion);
                    s.setDate(4, Date.valueOf(cmd.shiftDate()));
                    s.setString(5, cmd.shiftCode().name());
                    s.setString(6, cmd.primaryOncallUserId());
                    if (cmd.backupOncallUserId() == null) s.setNull(7, Types.OTHER); else s.setString(7, cmd.backupOncallUserId());
                    if (cmd.note() == null) s.setNull(8, Types.VARCHAR); else s.setString(8, cmd.note());
                    s.setString(9, a.actorId());
                    instant(s, 10, at);
                    s.executeUpdate();
                }
            } else {
                try (var s = statement(c, "UPDATE public.mop_oncall_roster SET version=?,primary_oncall_user_id=?::uuid,backup_oncall_user_id=?::uuid,note=?,updated_by=?::uuid,updated_at=? WHERE tenant_id=?::uuid AND id=?::uuid AND version=?")) {
                    s.setLong(1, nextVersion);
                    s.setString(2, cmd.primaryOncallUserId());
                    if (cmd.backupOncallUserId() == null) s.setNull(3, Types.OTHER); else s.setString(3, cmd.backupOncallUserId());
                    if (cmd.note() == null) s.setNull(4, Types.VARCHAR); else s.setString(4, cmd.note());
                    s.setString(5, a.actorId());
                    instant(s, 6, at);
                    s.setString(7, a.tenantId());
                    s.setString(8, id);
                    s.setLong(9, currentVersion);
                    if (s.executeUpdate() != 1) throw new DomainException(409, "VERSION_CONFLICT", "值班排班版本已变化，请刷新后重试");
                }
            }
            platform.audit(a.actorId(), a.tenantId(), "oncall.roster.upsert", "oncall_roster", id, "SUCCEEDED",
                "ONCALL_ROSTER_" + cmd.shiftDate() + "_" + cmd.shiftCode().name() + "_V" + nextVersion, a.traceId());
            return findRoster(c, a.tenantId(), cmd.shiftDate(), cmd.shiftCode()).orElseThrow(PostgresOnCallEscalationRepository::unavailable);
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    @Override
    public HandoffEntry recordHandoff(Access a, HandoffCommand cmd) {
        if (cmd == null) throw invalid();
        if (a.actorId().equals(cmd.incomingUserId()))
            throw new DomainException(400, "VALIDATION_ERROR", "交接班接班人不能与交班人相同");
        Connection c = platform.idempotentCommandConnection();
        try {
            member(c, a, "ticket_notes:write_internal", true, true);
            requireInternalMember(c, a.tenantId(), cmd.incomingUserId());
            lock(c, "mop.oncall_handoff|" + a.tenantId());
            if (cmd.rosterId() != null) {
                try (var s = statement(c, "SELECT 1 FROM public.mop_oncall_roster WHERE tenant_id=?::uuid AND id=?::uuid")) {
                    s.setString(1, a.tenantId());
                    s.setString(2, cmd.rosterId());
                    try (var r = s.executeQuery()) {
                        if (!r.next()) throw new DomainException(404, "RESOURCE_NOT_FOUND", "当前租户下不存在该值班排班记录");
                    }
                }
            }
            int unack = 0, inProgress = 0, pendingReview = 0;
            for (TicketRow row : loadTenantTickets(c, a.tenantId(), 500)) {
                State st = row.snapshot().state();
                if (st == State.OPEN) unack++;
                else if (st == State.ACCEPTED || st == State.IN_PROGRESS) inProgress++;
                else if (st == State.RESOLVED) pendingReview++;
            }
            String id = UUID.randomUUID().toString();
            Instant at = now(c);
            try (var s = statement(c, "INSERT INTO public.mop_oncall_handoff(id,tenant_id,roster_id,outgoing_user_id,incoming_user_id,handoff_local_time,unacknowledged_count,in_progress_count,pending_review_count,note,handed_off_at) VALUES(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,'18:00',?,?,?,?,?)")) {
                s.setString(1, id);
                s.setString(2, a.tenantId());
                if (cmd.rosterId() == null) s.setNull(3, Types.OTHER); else s.setString(3, cmd.rosterId());
                s.setString(4, a.actorId());
                s.setString(5, cmd.incomingUserId());
                s.setInt(6, unack);
                s.setInt(7, inProgress);
                s.setInt(8, pendingReview);
                s.setString(9, cmd.note());
                instant(s, 10, at);
                s.executeUpdate();
            }
            platform.audit(a.actorId(), a.tenantId(), "oncall.handoff.record", "oncall_handoff", id, "SUCCEEDED",
                "ONCALL_HANDOFF_UNACK_" + unack + "_INPROG_" + inProgress + "_REVIEW_" + pendingReview, a.traceId());
            return new HandoffEntry(id, a.tenantId(), cmd.rosterId(), a.actorId(), cmd.incomingUserId(), HANDOFF_LOCAL_TIME, unack, inProgress, pendingReview, cmd.note(), at);
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    @Override
    public List<EscalationPolicy> policies(Access a) {
        return read(a, "tickets:read", true, c -> loadPolicies(c, a.tenantId(), now(c)));
    }

    @Override
    public EscalationPolicy updatePolicy(Access a, PolicyUpdateCommand cmd) {
        if (cmd == null) throw invalid();
        Connection c = platform.idempotentCommandConnection();
        try {
            member(c, a, "configurations:write", true, true);
            lock(c, "mop.escalation_policy|" + a.tenantId() + "|" + cmd.tier().name());
            Optional<EscalationPolicy> existing = findPersistedPolicy(c, a.tenantId(), cmd.tier());
            long currentVersion = existing.map(EscalationPolicy::version).orElse(0L);
            if (cmd.expectedVersion() != currentVersion)
                throw new DomainException(409, "VERSION_CONFLICT", "升级策略版本已变化，请刷新后重试");
            long nextVersion = currentVersion + 1;
            Instant at = now(c);
            String stepsJson = serializeSteps(cmd.steps());
            if (existing.isEmpty()) {
                try (var s = statement(c, "INSERT INTO public.mop_escalation_policy(tenant_id,tier,version,unanswered_escalate_count,unanswered_retry_interval_sec,steps_json,policy_status,updated_by,updated_at) VALUES(?::uuid,?,?,?,NULL,?::jsonb,'CONFIG_PENDING_Q15',?::uuid,?)")) {
                    s.setString(1, a.tenantId());
                    s.setString(2, cmd.tier().name());
                    s.setLong(3, nextVersion);
                    s.setInt(4, cmd.unansweredEscalateCount());
                    s.setString(5, stepsJson);
                    s.setString(6, a.actorId());
                    instant(s, 7, at);
                    s.executeUpdate();
                }
            } else {
                try (var s = statement(c, "UPDATE public.mop_escalation_policy SET version=?,unanswered_escalate_count=?,steps_json=?::jsonb,updated_by=?::uuid,updated_at=? WHERE tenant_id=?::uuid AND tier=? AND version=?")) {
                    s.setLong(1, nextVersion);
                    s.setInt(2, cmd.unansweredEscalateCount());
                    s.setString(3, stepsJson);
                    s.setString(4, a.actorId());
                    instant(s, 5, at);
                    s.setString(6, a.tenantId());
                    s.setString(7, cmd.tier().name());
                    s.setLong(8, currentVersion);
                    if (s.executeUpdate() != 1) throw new DomainException(409, "VERSION_CONFLICT", "升级策略版本已变化，请刷新后重试");
                }
            }
            platform.audit(a.actorId(), a.tenantId(), "escalation_policy.update", "escalation_policy", a.tenantId() + ":" + cmd.tier().name(), "SUCCEEDED",
                "ESCALATION_POLICY_" + cmd.tier().name() + "_V" + nextVersion + "_STEPS_" + cmd.steps().size(), a.traceId());
            return findPersistedPolicy(c, a.tenantId(), cmd.tier()).orElseThrow(PostgresOnCallEscalationRepository::unavailable);
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    @Override
    public EscalationStepRun triggerEscalation(Access a, EscalationTriggerCommand cmd) {
        if (cmd == null) throw invalid();
        Connection c = platform.idempotentCommandConnection();
        try {
            member(c, a, "ticket_notes:write_internal", true, true);
            lock(c, "mop.escalation_run|" + a.tenantId() + "|" + cmd.ticketId());
            try (var s = statement(c, "SELECT 1 FROM public.mop_ticket WHERE tenant_id=?::uuid AND id=?::uuid")) {
                s.setString(1, a.tenantId());
                s.setString(2, cmd.ticketId());
                try (var r = s.executeQuery()) {
                    if (!r.next()) throw new DomainException(404, "RESOURCE_NOT_FOUND", "当前租户下不存在该工单，无法记录升级执行");
                }
            }
            Optional<EscalationPolicy> persisted = findPersistedPolicy(c, a.tenantId(), cmd.tier());
            Instant at = now(c);
            EscalationPolicy policy;
            if (persisted.isPresent()) {
                policy = persisted.get();
            } else {
                List<EscalationStep> defaults = OnCallEscalationRepository.defaultSteps(cmd.tier());
                try (var s = statement(c, "INSERT INTO public.mop_escalation_policy(tenant_id,tier,version,unanswered_escalate_count,unanswered_retry_interval_sec,steps_json,policy_status,updated_by,updated_at) VALUES(?::uuid,?,1,?,NULL,?::jsonb,'CONFIG_PENDING_Q15',?::uuid,?)")) {
                    s.setString(1, a.tenantId());
                    s.setString(2, cmd.tier().name());
                    s.setInt(3, DEFAULT_UNANSWERED_ESCALATE_COUNT);
                    s.setString(4, serializeSteps(defaults));
                    s.setString(5, a.actorId());
                    instant(s, 6, at);
                    s.executeUpdate();
                }
                policy = findPersistedPolicy(c, a.tenantId(), cmd.tier()).orElseThrow(PostgresOnCallEscalationRepository::unavailable);
            }
            if (cmd.stepOrder() > policy.steps().size())
                throw new DomainException(400, "VALIDATION_ERROR", "升级步骤序号超出当前档位升级链配置步数");
            EscalationStep currentStep = policy.steps().get(cmd.stepOrder() - 1);
            Integer escalatedToStep = null;
            TargetRole escalatedTargetRole = null;
            SmsNoticeStatus smsStatus = SmsNoticeStatus.NOT_REQUIRED;
            String smsSummary = null;

            boolean hasNextStep = cmd.stepOrder() < policy.steps().size();
            if (cmd.triggerCondition() == TriggerCondition.PHONE_UNANSWERED) {
                if (cmd.unansweredPhoneCount() >= policy.unansweredEscalateCount() && hasNextStep) {
                    EscalationStep nextStep = policy.steps().get(cmd.stepOrder());
                    escalatedToStep = nextStep.stepOrder();
                    escalatedTargetRole = nextStep.targetRole();
                    smsStatus = SmsNoticeStatus.INTENT_RECORDED_PENDING_GATEWAY;
                    smsSummary = "电话连续" + cmd.unansweredPhoneCount() + "次未接听，已自动升级至步骤" + escalatedToStep
                        + "(" + escalatedTargetRole.name() + ")并登记短信告知记录（外部短信网关待配置）";
                }
            } else if (hasNextStep) {
                EscalationStep nextStep = policy.steps().get(cmd.stepOrder());
                escalatedToStep = nextStep.stepOrder();
                escalatedTargetRole = nextStep.targetRole();
            }

            String id = UUID.randomUUID().toString();
            try (var s = statement(c, "INSERT INTO public.mop_escalation_step_run(id,tenant_id,ticket_id,tier,policy_version,step_order,trigger_condition,wait_minutes,target_role,unanswered_phone_count,escalated_to_step,escalated_target_role,sms_notice_status,sms_notice_summary,recorded_by,recorded_at) VALUES(?::uuid,?::uuid,?::uuid,?,?,?,?,?,?,?,?,?,?,?,?::uuid,?)")) {
                s.setString(1, id);
                s.setString(2, a.tenantId());
                s.setString(3, cmd.ticketId());
                s.setString(4, cmd.tier().name());
                s.setLong(5, policy.version());
                s.setInt(6, currentStep.stepOrder());
                s.setString(7, cmd.triggerCondition().name());
                s.setInt(8, currentStep.waitMinutes());
                s.setString(9, currentStep.targetRole().name());
                s.setInt(10, cmd.unansweredPhoneCount());
                if (escalatedToStep == null) s.setNull(11, Types.INTEGER); else s.setInt(11, escalatedToStep);
                if (escalatedTargetRole == null) s.setNull(12, Types.VARCHAR); else s.setString(12, escalatedTargetRole.name());
                s.setString(13, smsStatus.name());
                if (smsSummary == null) s.setNull(14, Types.VARCHAR); else s.setString(14, smsSummary);
                s.setString(15, a.actorId());
                instant(s, 16, at);
                s.executeUpdate();
            }
            platform.audit(a.actorId(), a.tenantId(), "escalation.step_run", "ticket_workflow", cmd.ticketId(), "SUCCEEDED",
                "ESCALATION_" + cmd.tier().name() + "_V" + policy.version() + "_STEP_" + currentStep.stepOrder()
                + "_TO_" + (escalatedToStep == null ? "NONE" : escalatedToStep) + "_SMS_" + smsStatus.name(), a.traceId());
            return new EscalationStepRun(
                id, a.tenantId(), cmd.ticketId(), cmd.tier(), policy.version(), currentStep.stepOrder(),
                cmd.triggerCondition(), currentStep.waitMinutes(), currentStep.targetRole(),
                cmd.unansweredPhoneCount(), escalatedToStep, escalatedTargetRole, smsStatus, smsSummary, a.actorId(), at
            );
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    @Override
    public SlaReportView slaReport(Access a, String period) {
        OnCallEscalationRepository.requireMonth(period);
        return read(a, "sla_scenarios:read", true, c -> {
            Instant observed = now(c);
            YearMonth ym = YearMonth.parse(period);
            Instant monthStart = ym.atDay(1).atStartOfDay(SHANGHAI).toInstant();
            Instant monthEnd = ym.plusMonths(1).atDay(1).atStartOfDay(SHANGHAI).toInstant();
            Instant effectiveEnd = observed.isBefore(monthEnd) && observed.isAfter(monthStart) ? observed : monthEnd;
            Interval agreedWindow = new Interval(monthStart, effectiveEnd.isAfter(monthStart) ? effectiveEnd : monthEnd);

            Tier currentTier = null;
            BigDecimal targetPct = null;
            Long monthlyFeeFen = null;
            try (var q = statement(c,"SELECT tier,availability_target,monthly_fee_fen FROM public.mop_service_tier WHERE tenant_id=?::uuid")) {
                q.setString(1,a.tenantId());try(var r=q.executeQuery()){if(r.next()){
                    currentTier=Tier.valueOf(r.getString(1));targetPct=r.getBigDecimal(2);long fee=r.getLong(3);if(!r.wasNull())monthlyFeeFen=fee;
                }}
            }
            List<TicketSample> samples=loadMonthTicketSamples(c,a.tenantId(),monthStart,monthEnd);
            var assets=new LinkedHashMap<String,String>();
            try(var q=statement(c,"SELECT id::text,name FROM public.mop_asset WHERE tenant_id=?::uuid ORDER BY id LIMIT 1001")){
                q.setString(1,a.tenantId());try(var r=q.executeQuery()){while(r.next())assets.put(r.getString(1),r.getString(2));}
            }
            if(assets.size()>1000)throw new DomainException(422,"SLA_REPORT_LIMIT","当前资产数量超过单次核算上限，未截断结果或冻结快照");
            var unavailable=new HashMap<String,List<Interval>>();var exclusions=new HashMap<String,List<Interval>>();
            var unknown=new HashMap<String,List<Interval>>();
            var assetTickets=new HashMap<String,Set<String>>();
            // Only monitoring events contribute unavailability. Customer/manual ticket duration is not a monitor signal.
            try(var q=statement(c,"SELECT asset_id::text,problem_at,recovered_at,ticket_id::text,sla_unknown_marked FROM public.mop_monitoring_event WHERE tenant_id=?::uuid AND problem_at<? AND (recovered_at IS NULL OR recovered_at>?) ORDER BY problem_at,id LIMIT 10001")){
                q.setString(1,a.tenantId());instant(q,2,agreedWindow.end());instant(q,3,monthStart);
                try(var r=q.executeQuery()){int count=0;while(r.next()){
                    if(++count>10000)throw new DomainException(422,"SLA_REPORT_LIMIT","监控事件超过单次核算上限，未截断结果");
                    String asset=r.getString(1);Instant from=time(r,"problem_at"),to=nullableTime(r,"recovered_at");
                    if(to==null||to.isAfter(agreedWindow.end()))to=agreedWindow.end();
                    if(from.isBefore(to))(r.getBoolean(5)?unknown:unavailable).computeIfAbsent(asset,k->new ArrayList<>()).add(new Interval(from,to));
                    if(r.getString(4)!=null)assetTickets.computeIfAbsent(asset,k->new HashSet<>()).add(r.getString(4));
                }}
            }
            try(var q=statement(c,"SELECT asset_id::text,starts_at,ends_at FROM public.mop_maintenance_window WHERE tenant_id=?::uuid AND sla_exclusion_confirmed AND status<>'CANCELLED' AND starts_at<? AND ends_at>? ORDER BY starts_at,id LIMIT 10001")){
                q.setString(1,a.tenantId());instant(q,2,agreedWindow.end());instant(q,3,monthStart);
                try(var r=q.executeQuery()){int count=0;while(r.next()){
                    if(++count>10000)throw new DomainException(422,"SLA_REPORT_LIMIT","维护排除区间超过单次核算上限，未截断结果");
                    exclusions.computeIfAbsent(r.getString(1),k->new ArrayList<>()).add(new Interval(time(r,"starts_at"),time(r,"ends_at")));
                }}
            }
            List<ServiceRow> services=new ArrayList<>();
            for(var asset:assets.entrySet()){
                Set<String> ids=assetTickets.getOrDefault(asset.getKey(),Set.of());
                services.add(SlaAvailabilityAndCompensation.evaluateService("ASSET_"+asset.getKey(),asset.getValue(),targetPct,agreedWindow,
                    exclusions.getOrDefault(asset.getKey(),List.of()),unavailable.getOrDefault(asset.getKey(),List.of()),unknown.getOrDefault(asset.getKey(),List.of()),
                    samples.stream().filter(t->ids.contains(t.ticketId())).toList(),List.of()));
            }
            BigDecimal meanAvail=null;
            if(!services.isEmpty()&&services.stream().allMatch(v->v.verifiedAvailabilityPct()!=null))
                meanAvail=services.stream().map(ServiceRow::verifiedAvailabilityPct).reduce(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal.valueOf(services.size()),4,RoundingMode.HALF_UP);
            long totalUnavailable=services.stream().mapToLong(ServiceRow::unavailableUnionSec).sum();
            long totalUnknown=services.stream().mapToLong(ServiceRow::unknownIntervalSec).sum();
            List<TicketSample> monitoredSamples=samples.stream().filter(t->assetTickets.values().stream().anyMatch(ids->ids.contains(t.ticketId()))).toList();
            List<Long> responseTimes=new ArrayList<>(),recoveryTimes=new ArrayList<>();int unrecovered=0;
            for(var t:monitoredSamples){if(t.responseActualAt()!=null&&!t.responseActualAt().isBefore(t.createdAt()))responseTimes.add(Duration.between(t.createdAt(),t.responseActualAt()).toSeconds());
                if(t.recoveryActualAt()!=null&&!t.recoveryActualAt().isBefore(t.createdAt()))recoveryTimes.add(Duration.between(t.createdAt(),t.recoveryActualAt()).toSeconds());else unrecovered++;}
            MonthlyEvaluation liveEval=SlaAvailabilityAndCompensation.evaluateMonthly(currentTier,monthlyFeeFen,samples);
            return new SlaReportView(a.tenantId(),period,currentTier,targetPct,meanAvail,totalUnavailable,
                SlaAvailabilityAndCompensation.computeLatency(responseTimes,0),SlaAvailabilityAndCompensation.computeLatency(recoveryTimes,unrecovered),unrecovered,totalUnknown,
                List.copyOf(services),loadMonthlyResult(c,a.tenantId(),period),liveEval,SlaAvailabilityAndCompensation.FORMULA_VERSION,observed);
        });
    }

    @Override
    public MonthlyResultRecord evaluateMonthlyResult(Access a, MonthlyResultCommand cmd) {
        if (cmd == null) throw invalid();
        Connection c = platform.idempotentCommandConnection();
        try {
            member(c, a, "ticket_notes:write_internal", true, true);
            lock(c, "mop.sla_monthly_result|" + a.tenantId() + "|" + cmd.reportMonth());
            Optional<MonthlyResultRecord> existing = loadMonthlyResult(c, a.tenantId(), cmd.reportMonth());
            if (existing.isPresent() && existing.get().frozen()) {
                throw new DomainException(409, "MONTHLY_RESULT_FROZEN", "该月份SLA月度快照已冻结，禁止修改或覆盖");
            }
            long currentVersion = existing.map(MonthlyResultRecord::version).orElse(0L);
            if (cmd.expectedVersion() != currentVersion)
                throw new DomainException(409, "VERSION_CONFLICT", "月度SLA结果版本已变化，请刷新后重试");

            YearMonth ym = YearMonth.parse(cmd.reportMonth());
            Instant monthStart = ym.atDay(1).atStartOfDay(SHANGHAI).toInstant();
            Instant monthEnd = ym.plusMonths(1).atDay(1).atStartOfDay(SHANGHAI).toInstant();

            Tier currentTier = null;
            Long monthlyFeeFen = null;
            try (var s = statement(c, "SELECT tier,monthly_fee_fen FROM public.mop_service_tier WHERE tenant_id=?::uuid")) {
                s.setString(1, a.tenantId());
                try (var r = s.executeQuery()) {
                    if (r.next()) {
                        currentTier = Tier.valueOf(r.getString("tier"));
                        long f = r.getLong("monthly_fee_fen");
                        if (!r.wasNull()) monthlyFeeFen = f;
                    }
                }
            }

            if(currentTier==null)throw new DomainException(422,"SERVICE_TIER_UNCONFIGURED","当前租户未登记服务档位，不能默认核算");
            List<TicketSample> samples = loadMonthTicketSamples(c, a.tenantId(), monthStart, monthEnd);
            MonthlyEvaluation eval = SlaAvailabilityAndCompensation.evaluateMonthly(currentTier, monthlyFeeFen, samples);
            long nextVersion = currentVersion + 1;
            String id = existing.map(MonthlyResultRecord::id).orElseGet(() -> UUID.randomUUID().toString());
            Instant at = now(c);
            Instant frozenAt = cmd.freeze() ? at : null;
            String itemsJson = serializeJudgments(eval.items());

            if (existing.isEmpty()) {
                try (var s = statement(c, "INSERT INTO public.mop_sla_monthly_result(id,tenant_id,report_month,version,tier_snapshot,total_ticket_count,applicable_count,met_count,not_met_count,unresolved_count,not_applicable_count,achievement_numerator,achievement_denominator,compensation_ratio,monthly_fee_fen,compensation_amount_fen,compensation_status,formula_version,items_json,frozen,frozen_at,evaluated_by,evaluated_at) VALUES(?::uuid,?::uuid,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,'CONFIG_PENDING_Q26',?,?::jsonb,?,?,?::uuid,?)")) {
                    s.setString(1, id);
                    s.setString(2, a.tenantId());
                    s.setString(3, cmd.reportMonth());
                    s.setLong(4, nextVersion);
                    s.setString(5, eval.tierSnapshot().name());
                    s.setInt(6, eval.totalTicketCount());
                    s.setInt(7, eval.applicableCount());
                    s.setInt(8, eval.metCount());
                    s.setInt(9, eval.notMetCount());
                    s.setInt(10, eval.unresolvedCount());
                    s.setInt(11, eval.notApplicableCount());
                    if (eval.achievementNumerator() == null) s.setNull(12, Types.INTEGER); else s.setInt(12, eval.achievementNumerator());
                    if (eval.achievementDenominator() == null) s.setNull(13, Types.INTEGER); else s.setInt(13, eval.achievementDenominator());
                    s.setBigDecimal(14, eval.compensationRatio());
                    if (eval.monthlyFeeFen() == null) s.setNull(15, Types.BIGINT); else s.setLong(15, eval.monthlyFeeFen());
                    s.setString(16, eval.formulaVersion());
                    s.setString(17, itemsJson);
                    s.setBoolean(18, cmd.freeze());
                    if (frozenAt == null) s.setNull(19, Types.TIMESTAMP_WITH_TIMEZONE); else instant(s, 19, frozenAt);
                    s.setString(20, a.actorId());
                    instant(s, 21, at);
                    s.executeUpdate();
                }
            } else {
                try (var s = statement(c, "UPDATE public.mop_sla_monthly_result SET version=?,tier_snapshot=?,total_ticket_count=?,applicable_count=?,met_count=?,not_met_count=?,unresolved_count=?,not_applicable_count=?,achievement_numerator=?,achievement_denominator=?,compensation_ratio=?,monthly_fee_fen=?,formula_version=?,items_json=?::jsonb,frozen=?,frozen_at=?,evaluated_by=?::uuid,evaluated_at=? WHERE tenant_id=?::uuid AND report_month=? AND version=?")) {
                    s.setLong(1, nextVersion);
                    s.setString(2, eval.tierSnapshot().name());
                    s.setInt(3, eval.totalTicketCount());
                    s.setInt(4, eval.applicableCount());
                    s.setInt(5, eval.metCount());
                    s.setInt(6, eval.notMetCount());
                    s.setInt(7, eval.unresolvedCount());
                    s.setInt(8, eval.notApplicableCount());
                    if (eval.achievementNumerator() == null) s.setNull(9, Types.INTEGER); else s.setInt(9, eval.achievementNumerator());
                    if (eval.achievementDenominator() == null) s.setNull(10, Types.INTEGER); else s.setInt(10, eval.achievementDenominator());
                    s.setBigDecimal(11, eval.compensationRatio());
                    if (eval.monthlyFeeFen() == null) s.setNull(12, Types.BIGINT); else s.setLong(12, eval.monthlyFeeFen());
                    s.setString(13, eval.formulaVersion());
                    s.setString(14, itemsJson);
                    s.setBoolean(15, cmd.freeze());
                    if (frozenAt == null) s.setNull(16, Types.TIMESTAMP_WITH_TIMEZONE); else instant(s, 16, frozenAt);
                    s.setString(17, a.actorId());
                    instant(s, 18, at);
                    s.setString(19, a.tenantId());
                    s.setString(20, cmd.reportMonth());
                    s.setLong(21, currentVersion);
                    if (s.executeUpdate() != 1) throw new DomainException(409, "VERSION_CONFLICT", "月度SLA结果版本已变化，请刷新后重试");
                }
            }
            platform.audit(a.actorId(), a.tenantId(), cmd.freeze() ? "sla.monthly_result.freeze" : "sla.monthly_result.evaluate",
                "sla_monthly_result", id, "SUCCEEDED",
                "SLA_MONTHLY_" + cmd.reportMonth() + "_V" + nextVersion + "_MET_" + eval.metCount() + "_OF_" + eval.applicableCount() + "_FROZEN_" + cmd.freeze(), a.traceId());
            return loadMonthlyResult(c, a.tenantId(), cmd.reportMonth()).orElseThrow(PostgresOnCallEscalationRepository::unavailable);
        } catch (SQLException e) {
            throw unavailable();
        }
    }

    private List<TicketRow> loadTenantTickets(Connection c, String tenant, int limit) throws SQLException {
        var list = new ArrayList<TicketRow>();
        try (var s = statement(c, "SELECT t.id::text AS ticket_id, t.created_at, e.snapshot::text AS snapshot "
            + "FROM public.mop_ticket t JOIN LATERAL (SELECT snapshot FROM public.mop_ticket_workflow_event WHERE tenant_id=t.tenant_id AND ticket_id=t.id ORDER BY version DESC LIMIT 1) e ON true "
            + "WHERE t.tenant_id=?::uuid ORDER BY t.created_at DESC, t.id DESC LIMIT ?")) {
            s.setString(1, tenant);
            s.setInt(2, limit);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    list.add(new TicketRow(r.getString("ticket_id"), time(r, "created_at"), decodeSnapshot(r.getString("snapshot"))));
                }
            }
        }
        return list;
    }

    private List<TicketSample> loadMonthTicketSamples(Connection c, String tenant, Instant monthStart, Instant monthEnd) throws SQLException {
        var list = new ArrayList<TicketSample>();
        try (var s = statement(c, "SELECT t.id::text AS ticket_id, t.created_at, e.snapshot::text AS snapshot "
            + "FROM public.mop_ticket t JOIN LATERAL (SELECT snapshot FROM public.mop_ticket_workflow_event WHERE tenant_id=t.tenant_id AND ticket_id=t.id ORDER BY version DESC LIMIT 1) e ON true "
            + "WHERE t.tenant_id=?::uuid AND t.created_at<? AND (e.snapshot->>'resolvedAt' IS NULL OR (e.snapshot->>'resolvedAt')::timestamptz>=?) AND e.snapshot->>'state'<>'CANCELLED' ORDER BY t.created_at ASC, t.id ASC LIMIT 2001")) {
            s.setString(1, tenant);
            instant(s, 2, monthEnd);
            instant(s, 3, monthStart);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    if(list.size()>=2000)throw new DomainException(422,"SLA_REPORT_LIMIT","工单样本超过2000条，未截断结果或冻结快照");
                    TicketLifecycle.Snapshot snap = decodeSnapshot(r.getString("snapshot"));
                    var recovery=PostgresTicketWorkflowRepository.monitoringRecovery(c,tenant,r.getString("ticket_id"));
                    list.add(new TicketSample(
                        r.getString("ticket_id"),
                        Priority.valueOf(snap.sla().priority()),
                        snap.sla().tier(),
                        Exclusion.NONE,
                        null,
                        snap.openedAt(),
                        snap.sla().responseDueAt(),
                        snap.acceptedAt(),
                        snap.sla().recoveryDueAt(),
                        recovery.linked()?recovery.recoveredAt():snap.resolvedAt()
                    ));
                }
            }
        }
        return list;
    }

    private static TicketLifecycle.Snapshot decodeSnapshot(String json) {
        try {
            return JSON.readValue(json, TicketLifecycle.Snapshot.class);
        } catch (Exception e) {
            throw unavailable();
        }
    }

    private Optional<MonthlyResultRecord> loadMonthlyResult(Connection c, String tenant, String reportMonth) throws SQLException {
        try (var s = statement(c, "SELECT id::text,tenant_id::text,report_month,version,tier_snapshot,total_ticket_count,applicable_count,met_count,not_met_count,unresolved_count,not_applicable_count,achievement_numerator,achievement_denominator,compensation_ratio,monthly_fee_fen,compensation_amount_fen,compensation_status,formula_version,items_json::text,frozen,frozen_at,evaluated_by::text,evaluated_at FROM public.mop_sla_monthly_result WHERE tenant_id=?::uuid AND report_month=?")) {
            s.setString(1, tenant);
            s.setString(2, reportMonth);
            try (var r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                int applicable = r.getInt("applicable_count");
                int met = r.getInt("met_count");
                int notMet = r.getInt("not_met_count");
                Integer num = r.getInt("achievement_numerator");
                if (r.wasNull()) num = null;
                Integer den = r.getInt("achievement_denominator");
                if (r.wasNull()) den = null;
                BigDecimal ratio = r.getBigDecimal("compensation_ratio");
                Long fee = r.getLong("monthly_fee_fen");
                if (r.wasNull()) fee = null;
                Long compAmount = r.getLong("compensation_amount_fen");
                if (r.wasNull()) compAmount = null;
                BigDecimal ratePct = applicable > 0
                    ? BigDecimal.valueOf(met * 100.0).divide(BigDecimal.valueOf(applicable), 2, RoundingMode.HALF_UP)
                    : null;
                Long fixedCandidate = null;
                Long shortfallCandidate = null;
                if (fee != null && fee > 0 && applicable > 0 && notMet > 0) {
                    fixedCandidate = BigDecimal.valueOf(fee).multiply(ratio).setScale(0, RoundingMode.HALF_UP).longValue();
                    shortfallCandidate = BigDecimal.valueOf(fee).multiply(ratio).multiply(BigDecimal.valueOf(applicable - met))
                        .divide(BigDecimal.valueOf(applicable), 0, RoundingMode.HALF_UP).longValue();
                }
                if(SlaAvailabilityAndCompensation.FORMULA_VERSION.equals(r.getString("formula_version"))){
                    if(fixedCandidate!=null)fixedCandidate=BigDecimal.valueOf(fee).multiply(ratio).divide(BigDecimal.valueOf(100),0,RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).longValueExact();
                    shortfallCandidate=null;
                }
                return Optional.of(new MonthlyResultRecord(
                    r.getString("id"),
                    r.getString("tenant_id"),
                    r.getString("report_month"),
                    r.getLong("version"),
                    Tier.valueOf(r.getString("tier_snapshot")),
                    r.getInt("total_ticket_count"),
                    applicable,
                    met,
                    notMet,
                    r.getInt("unresolved_count"),
                    r.getInt("not_applicable_count"),
                    num,
                    den,
                    ratePct,
                    ratio,
                    fee,
                    compAmount,
                    r.getString("compensation_status"),
                    fixedCandidate,
                    shortfallCandidate,
                    r.getString("formula_version"),
                    deserializeJudgments(r.getString("items_json")),
                    r.getBoolean("frozen"),
                    nullableTime(r, "frozen_at"),
                    r.getString("evaluated_by"),
                    time(r, "evaluated_at")
                ));
            }
        }
    }

    private Optional<RosterEntry> findRoster(Connection c, String tenant, LocalDate date, ShiftCode code) throws SQLException {
        try (var s = statement(c, "SELECT id::text,tenant_id::text,version,shift_date,shift_code,handoff_local_time,primary_oncall_user_id::text,backup_oncall_user_id::text,note,roster_policy_status,updated_by::text,updated_at FROM public.mop_oncall_roster WHERE tenant_id=?::uuid AND shift_date=? AND shift_code=?")) {
            s.setString(1, tenant);
            s.setDate(2, Date.valueOf(date));
            s.setString(3, code.name());
            try (var r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                return Optional.of(mapRoster(r));
            }
        }
    }

    private List<RosterEntry> loadRosters(Connection c, String tenant, int limit) throws SQLException {
        var list = new ArrayList<RosterEntry>();
        try (var s = statement(c, "SELECT id::text,tenant_id::text,version,shift_date,shift_code,handoff_local_time,primary_oncall_user_id::text,backup_oncall_user_id::text,note,roster_policy_status,updated_by::text,updated_at FROM public.mop_oncall_roster WHERE tenant_id=?::uuid ORDER BY shift_date DESC, shift_code ASC LIMIT ?")) {
            s.setString(1, tenant);
            s.setInt(2, limit);
            try (var r = s.executeQuery()) {
                while (r.next()) list.add(mapRoster(r));
            }
        }
        return list;
    }

    private static RosterEntry mapRoster(ResultSet r) throws SQLException {
        return new RosterEntry(
            r.getString("id"),
            r.getString("tenant_id"),
            r.getLong("version"),
            r.getDate("shift_date").toLocalDate(),
            ShiftCode.valueOf(r.getString("shift_code")),
            r.getString("handoff_local_time"),
            r.getString("primary_oncall_user_id"),
            r.getString("backup_oncall_user_id"),
            r.getString("note"),
            r.getString("roster_policy_status"),
            r.getString("updated_by"),
            time(r, "updated_at")
        );
    }

    private List<HandoffEntry> loadHandoffs(Connection c, String tenant, int limit) throws SQLException {
        var list = new ArrayList<HandoffEntry>();
        try (var s = statement(c, "SELECT id::text,tenant_id::text,roster_id::text,outgoing_user_id::text,incoming_user_id::text,handoff_local_time,unacknowledged_count,in_progress_count,pending_review_count,note,handed_off_at FROM public.mop_oncall_handoff WHERE tenant_id=?::uuid ORDER BY handed_off_at DESC, id DESC LIMIT ?")) {
            s.setString(1, tenant);
            s.setInt(2, limit);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    list.add(new HandoffEntry(
                        r.getString("id"),
                        r.getString("tenant_id"),
                        r.getString("roster_id"),
                        r.getString("outgoing_user_id"),
                        r.getString("incoming_user_id"),
                        r.getString("handoff_local_time"),
                        r.getInt("unacknowledged_count"),
                        r.getInt("in_progress_count"),
                        r.getInt("pending_review_count"),
                        r.getString("note"),
                        time(r, "handed_off_at")
                    ));
                }
            }
        }
        return list;
    }

    private Optional<EscalationPolicy> findPersistedPolicy(Connection c, String tenant, PolicyTier tier) throws SQLException {
        try (var s = statement(c, "SELECT tenant_id::text,tier,version,unanswered_escalate_count,unanswered_retry_interval_sec,steps_json::text,policy_status,updated_by::text,updated_at FROM public.mop_escalation_policy WHERE tenant_id=?::uuid AND tier=?")) {
            s.setString(1, tenant);
            s.setString(2, tier.name());
            try (var r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                Integer retry = r.getInt("unanswered_retry_interval_sec");
                if (r.wasNull()) retry = null;
                return Optional.of(new EscalationPolicy(
                    r.getString("tenant_id"),
                    PolicyTier.valueOf(r.getString("tier")),
                    r.getLong("version"),
                    r.getInt("unanswered_escalate_count"),
                    retry,
                    deserializeSteps(r.getString("steps_json")),
                    r.getString("policy_status"),
                    r.getString("updated_by"),
                    time(r, "updated_at"),
                    true
                ));
            }
        }
    }

    private List<EscalationPolicy> loadPolicies(Connection c, String tenant, Instant observed) throws SQLException {
        Map<PolicyTier, EscalationPolicy> map = new EnumMap<>(PolicyTier.class);
        try (var s = statement(c, "SELECT tenant_id::text,tier,version,unanswered_escalate_count,unanswered_retry_interval_sec,steps_json::text,policy_status,updated_by::text,updated_at FROM public.mop_escalation_policy WHERE tenant_id=?::uuid")) {
            s.setString(1, tenant);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    PolicyTier tier = PolicyTier.valueOf(r.getString("tier"));
                    Integer retry = r.getInt("unanswered_retry_interval_sec");
                    if (r.wasNull()) retry = null;
                    map.put(tier, new EscalationPolicy(
                        r.getString("tenant_id"), tier, r.getLong("version"),
                        r.getInt("unanswered_escalate_count"), retry,
                        deserializeSteps(r.getString("steps_json")),
                        r.getString("policy_status"), r.getString("updated_by"), time(r, "updated_at"), true
                    ));
                }
            }
        }
        var out = new ArrayList<EscalationPolicy>(4);
        for (PolicyTier tier : PolicyTier.values()) {
            out.add(map.getOrDefault(tier, new EscalationPolicy(
                tenant, tier, 0L, DEFAULT_UNANSWERED_ESCALATE_COUNT, null,
                OnCallEscalationRepository.defaultSteps(tier), "CONFIG_PENDING_Q15", null, observed, false
            )));
        }
        return out;
    }

    private List<EscalationStepRun> loadStepRuns(Connection c, String tenant, int limit) throws SQLException {
        var list = new ArrayList<EscalationStepRun>();
        try (var s = statement(c, "SELECT id::text,tenant_id::text,ticket_id::text,tier,policy_version,step_order,trigger_condition,wait_minutes,target_role,unanswered_phone_count,escalated_to_step,escalated_target_role,sms_notice_status,sms_notice_summary,recorded_by::text,recorded_at FROM public.mop_escalation_step_run WHERE tenant_id=?::uuid ORDER BY recorded_at DESC, id DESC LIMIT ?")) {
            s.setString(1, tenant);
            s.setInt(2, limit);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    Integer escStep = r.getInt("escalated_to_step");
                    if (r.wasNull()) escStep = null;
                    String escRoleRaw = r.getString("escalated_target_role");
                    list.add(new EscalationStepRun(
                        r.getString("id"),
                        r.getString("tenant_id"),
                        r.getString("ticket_id"),
                        PolicyTier.valueOf(r.getString("tier")),
                        r.getLong("policy_version"),
                        r.getInt("step_order"),
                        TriggerCondition.valueOf(r.getString("trigger_condition")),
                        r.getInt("wait_minutes"),
                        TargetRole.valueOf(r.getString("target_role")),
                        r.getInt("unanswered_phone_count"),
                        escStep,
                        escRoleRaw == null ? null : TargetRole.valueOf(escRoleRaw),
                        SmsNoticeStatus.valueOf(r.getString("sms_notice_status")),
                        r.getString("sms_notice_summary"),
                        r.getString("recorded_by"),
                        time(r, "recorded_at")
                    ));
                }
            }
        }
        return list;
    }

    private static String serializeSteps(List<EscalationStep> steps) {
        ArrayNode arr = JSON.createArrayNode();
        for (EscalationStep s : steps) {
            ObjectNode n = arr.addObject();
            n.put("step_order", s.stepOrder());
            n.put("trigger_condition", s.triggerCondition().name());
            n.put("wait_minutes", s.waitMinutes());
            n.put("target_role", s.targetRole().name());
        }
        return arr.toString();
    }

    private static List<EscalationStep> deserializeSteps(String raw) {
        try {
            JsonNode arr = JSON.readTree(raw);
            var list = new ArrayList<EscalationStep>();
            for (JsonNode n : arr) {
                list.add(new EscalationStep(
                    n.get("step_order").intValue(),
                    TriggerCondition.valueOf(n.get("trigger_condition").textValue()),
                    n.get("wait_minutes").intValue(),
                    TargetRole.valueOf(n.get("target_role").textValue())
                ));
            }
            return list;
        } catch (Exception e) {
            throw unavailable();
        }
    }

    private static String serializeJudgments(List<TicketJudgment> items) {
        ArrayNode arr = JSON.createArrayNode();
        for (TicketJudgment j : items) {
            ObjectNode n = arr.addObject();
            n.put("ticket_id", j.ticketId());
            n.put("priority", j.priority());
            n.put("tier_snapshot", j.tierSnapshot());
            if (j.applicable() == null) n.putNull("applicable"); else n.put("applicable", j.applicable());
            n.put("outcome", j.outcome());
            n.put("response_check", j.responseCheck());
            n.put("recovery_check", j.recoveryCheck());
            if (j.responseDueAt() == null) n.putNull("response_due_at"); else n.put("response_due_at", j.responseDueAt().toString());
            if (j.responseActualAt() == null) n.putNull("response_actual_at"); else n.put("response_actual_at", j.responseActualAt().toString());
            if (j.recoveryDueAt() == null) n.putNull("recovery_due_at"); else n.put("recovery_due_at", j.recoveryDueAt().toString());
            if (j.recoveryActualAt() == null) n.putNull("recovery_actual_at"); else n.put("recovery_actual_at", j.recoveryActualAt().toString());
            ArrayNode reasons = n.putArray("reasons");
            for (String r : j.reasons()) reasons.add(r);
        }
        return arr.toString();
    }

    private static List<TicketJudgment> deserializeJudgments(String raw) {
        try {
            JsonNode arr = JSON.readTree(raw);
            var list = new ArrayList<TicketJudgment>();
            for (JsonNode n : arr) {
                var reasons = new ArrayList<String>();
                if (n.has("reasons") && n.get("reasons").isArray()) {
                    for (JsonNode r : n.get("reasons")) reasons.add(r.textValue());
                }
                list.add(new TicketJudgment(
                    n.get("ticket_id").textValue(),
                    n.get("priority").textValue(),
                    n.get("tier_snapshot").textValue(),
                    n.get("applicable").isNull() ? null : n.get("applicable").booleanValue(),
                    n.get("outcome").textValue(),
                    n.get("response_check").textValue(),
                    n.get("recovery_check").textValue(),
                    n.get("response_due_at").isNull() ? null : Instant.parse(n.get("response_due_at").textValue()),
                    n.get("response_actual_at").isNull() ? null : Instant.parse(n.get("response_actual_at").textValue()),
                    n.get("recovery_due_at").isNull() ? null : Instant.parse(n.get("recovery_due_at").textValue()),
                    n.get("recovery_actual_at").isNull() ? null : Instant.parse(n.get("recovery_actual_at").textValue()),
                    reasons
                ));
            }
            return list;
        } catch (Exception e) {
            throw unavailable();
        }
    }

    private static String member(Connection c, Access a, String capability, boolean internalOnly, boolean lock) throws SQLException {
        if (a == null) throw expired();
        PostgresAuthorization.require(c, a.actorId(), a.tenantId(), a.permissionVersion(), capability);
        try (var s = statement(c, "SELECT m.role_code FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active AND m.permission_version=?" + (lock ? " FOR SHARE OF m,u" : ""))) {
            s.setString(1, a.tenantId());
            s.setString(2, a.actorId());
            s.setLong(3, a.permissionVersion());
            try (var r = s.executeQuery()) {
                if (!r.next()) throw expired();
                String role = r.getString(1);
                if (internalOnly && !Capabilities.internal(role))
                    throw new DomainException(403, "ACTION_FORBIDDEN", "当前身份无权访问值班看板或SLA核算台账");
                return role;
            }
        }
    }

    private static void requireInternalMember(Connection c, String tenant, String userId) throws SQLException {
        try (var s = statement(c, "SELECT m.role_code FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=?::uuid AND m.user_id=?::uuid AND m.active AND u.active")) {
            s.setString(1, tenant);
            s.setString(2, userId);
            try (var r = s.executeQuery()) {
                if (!r.next() || !Capabilities.internal(r.getString(1)))
                    throw new DomainException(404, "RESOURCE_NOT_FOUND", "当前租户下不存在该内部值班成员");
            }
        }
    }

    private static void lock(Connection c, String key) throws SQLException {
        try (var s = statement(c, "SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
            s.setString(1, key);
            try (var r = s.executeQuery()) { r.next(); }
        }
    }

    @Override
    public List<String> readinessProblems() {
        try (Connection c = source.getConnection()) {
            c.setReadOnly(true);
            try (var s = statement(c, "SELECT current_database()='mop_test' AND current_user='mop_test_runtime'"
                + " AND has_table_privilege(current_user,'public.mop_oncall_roster','SELECT,INSERT') AND NOT has_table_privilege(current_user,'public.mop_oncall_roster','DELETE,TRUNCATE')"
                + " AND has_table_privilege(current_user,'public.mop_oncall_handoff','SELECT,INSERT') AND NOT has_table_privilege(current_user,'public.mop_oncall_handoff','UPDATE,DELETE,TRUNCATE') AND NOT has_any_column_privilege(current_user,'public.mop_oncall_handoff','UPDATE')"
                + " AND has_table_privilege(current_user,'public.mop_escalation_policy','SELECT,INSERT') AND NOT has_table_privilege(current_user,'public.mop_escalation_policy','DELETE,TRUNCATE')"
                + " AND has_table_privilege(current_user,'public.mop_escalation_step_run','SELECT,INSERT') AND NOT has_table_privilege(current_user,'public.mop_escalation_step_run','UPDATE,DELETE,TRUNCATE') AND NOT has_any_column_privilege(current_user,'public.mop_escalation_step_run','UPDATE')"
                + " AND has_table_privilege(current_user,'public.mop_sla_monthly_result','SELECT,INSERT') AND NOT has_table_privilege(current_user,'public.mop_sla_monthly_result','DELETE,TRUNCATE')"
                + " AND (SELECT count(*)=5 FROM pg_trigger WHERE tgname IN ('mop_oncall_roster_guard','mop_oncall_handoff_guard','mop_escalation_policy_guard','mop_escalation_step_run_guard','mop_sla_monthly_result_guard') AND tgenabled='O')");
                 var r = s.executeQuery()) {
                if (!r.next() || !r.getBoolean(1)) return List.of("ONCALL_ESCALATION_SCHEMA_OR_ACL_INVALID");
            }
            return List.of();
        } catch (SQLException | RuntimeException e) {
            return List.of("ONCALL_ESCALATION_SCHEMA_OR_ACCESS_UNAVAILABLE");
        }
    }

    private static PreparedStatement statement(Connection c, String sql) throws SQLException {
        var s = c.prepareStatement(sql);
        s.setQueryTimeout(10);
        return s;
    }
    private static void instant(PreparedStatement s, int n, Instant t) throws SQLException {
        s.setObject(n, OffsetDateTime.ofInstant(t, ZoneOffset.UTC));
    }
    private static Instant time(ResultSet r, String k) throws SQLException {
        return r.getObject(k, OffsetDateTime.class).toInstant();
    }
    private static Instant nullableTime(ResultSet r, String k) throws SQLException {
        OffsetDateTime v = r.getObject(k, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }
    private static Instant now(Connection c) throws SQLException {
        try (var s = statement(c, "SELECT date_trunc('milliseconds',clock_timestamp())"); var r = s.executeQuery()) {
            r.next();
            return r.getObject(1, OffsetDateTime.class).toInstant();
        }
    }
    private static DomainException invalid() { return new DomainException(400, "VALIDATION_ERROR", "值班看板、升级策略或月度SLA请求参数无效"); }
    private static DomainException expired() { return new DomainException(401, "SESSION_EXPIRED", "当前会话或成员上下文已失效"); }
    private static DomainException unavailable() { return new DomainException(503, "UPSTREAM_UNAVAILABLE", "值班与SLA持久化不可用，未返回替代结果"); }
}
