package com.arthvritt.platform.adminiam;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * The record-level maker≠checker primitive (C4, X11, M4d). Reusable across bounded contexts: a checker
 * command calls {@link #evaluate} with the originating aggregate and the maker's proposal event type;
 * the gate finds the most-recent <i>unanswered</i> proposal envelope in the {@code sys_audit_event}
 * stream and compares its {@code actor_id} (the human identity, not role/session) to the checker's.
 * "Unanswered" = no later {@code MakerChecker.Approved}/{@code Blocked} for the same aggregate, ordered
 * by {@code event_id} (UUIDv7, time-ordered — microsecond-safe, unlike {@code recorded_at}). The stream
 * IS the state — no table; {@link #pendingApprovals} is a projection over it. The gate is a pure
 * decision; the caller emits the {@code Blocked}/{@code Approved} envelopes.
 */
@Service
public class MakerCheckerGate {

    public static final String APPROVED_EVENT = "admin_iam.MakerChecker.Approved";
    public static final String BLOCKED_EVENT = "admin_iam.MakerChecker.Blocked";

    private final JdbcTemplate jdbc;

    public MakerCheckerGate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Reads the most-recent <i>unanswered</i> proposal on {@code (aggregateType, aggregateId)} and
     * decides: no open proposal, blocked ({@code makerActorId == checkerActorId}, C4), or clear. Also
     * returns the {@code aggregate_version} captured in the proposal, so the checker can anchor the
     * transition to the state the maker actually proposed.
     */
    public MakerCheckerDecision evaluate(String aggregateType, UUID aggregateId,
                                         String proposalEventType, UUID checkerActorId) {
        Proposal proposal = jdbc.query(
                "SELECT p.actor->>'actor_id' AS maker, p.aggregate_version AS version FROM sys_audit_event p "
                        + "WHERE p.aggregate_type = ? AND p.aggregate_id = ? AND p.event_type = ? "
                        + "AND NOT EXISTS (SELECT 1 FROM sys_audit_event a WHERE a.aggregate_id = p.aggregate_id "
                        + "  AND a.event_type IN (?, ?) AND a.event_id > p.event_id) "
                        + "ORDER BY p.event_id DESC LIMIT 1",
                rs -> rs.next()
                        ? new Proposal(UUID.fromString(rs.getString("maker")), rs.getInt("version"))
                        : null,
                aggregateType, aggregateId, proposalEventType, APPROVED_EVENT, BLOCKED_EVENT);
        if (proposal == null) {
            return new MakerCheckerDecision(false, false, null, 0);
        }
        return new MakerCheckerDecision(true, proposal.makerActorId().equals(checkerActorId),
                proposal.makerActorId(), proposal.proposedVersion());
    }

    /** True if an unanswered proposal already exists — used to forbid stacking a second open proposal. */
    public boolean hasOpenProposal(String aggregateType, UUID aggregateId, String proposalEventType) {
        return evaluate(aggregateType, aggregateId, proposalEventType, null).hasProposal();
    }

    /**
     * Unanswered proposals of {@code proposalEventType}, excluding those the requesting actor proposed
     * (B4 §6.5 — UX defence-in-depth; C4 enforcement is still in {@link #evaluate} at the handler).
     */
    public List<PendingApproval> pendingApprovals(String proposalEventType, UUID excludingActorId) {
        return jdbc.query(
                "SELECT p.aggregate_id, p.actor->>'actor_id' AS maker FROM sys_audit_event p "
                        + "WHERE p.event_type = ? AND p.actor->>'actor_id' <> ? "
                        + "AND NOT EXISTS (SELECT 1 FROM sys_audit_event a WHERE a.aggregate_id = p.aggregate_id "
                        + "  AND a.event_type IN (?, ?) AND a.event_id > p.event_id)",
                (rs, n) -> new PendingApproval(rs.getObject("aggregate_id", UUID.class),
                        UUID.fromString(rs.getString("maker"))),
                proposalEventType, excludingActorId.toString(), APPROVED_EVENT, BLOCKED_EVENT);
    }

    public record MakerCheckerDecision(boolean hasProposal, boolean blocked, UUID makerActorId,
                                       int proposedVersion) {
    }

    public record PendingApproval(UUID aggregateId, UUID makerActorId) {
    }

    private record Proposal(UUID makerActorId, int proposedVersion) {
    }
}
