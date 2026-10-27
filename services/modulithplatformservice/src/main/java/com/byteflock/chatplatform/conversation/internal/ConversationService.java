package com.byteflock.chatplatform.conversation.internal;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.byteflock.chatplatform.conversation.AssignmentState;
import com.byteflock.chatplatform.conversation.Channels;
import com.byteflock.chatplatform.conversation.CommandRequest;
import com.byteflock.chatplatform.conversation.ConversationAccessDeniedException;
import com.byteflock.chatplatform.conversation.ConversationChange;
import com.byteflock.chatplatform.conversation.ConversationConflictException;
import com.byteflock.chatplatform.conversation.ConversationNotFoundException;
import com.byteflock.chatplatform.conversation.ConversationView;
import com.byteflock.chatplatform.conversation.InboxPage;
import com.byteflock.chatplatform.outbox.OutboxAppender;
import com.byteflock.chatplatform.tenancy.Caller;
import com.byteflock.chatplatform.tenancy.Inbox;
import com.byteflock.chatplatform.tenancy.TenantDirectory;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Conversation commands: start/resume, read, inbox pages and the lifecycle transitions of
 * {@code docs/architecture/domain-model.md} section 5. Each command runs in one transaction that locks the conversation
 * row, checks the documented order (satisfied no-op → {@code expectedVersion} → transition table), writes the state and
 * its state event, and appends exactly one {@code conversation.updated} outbox row when the version increments (R-P23).
 */
@Service
public class ConversationService {

	private static final Set<String> VIEWS = Set.of("queue", "mine", "all");

	private final TransactionTemplate tx;

	private final ConversationAccess access;

	private final ConversationRepository conversations;

	private final ParticipantRepository participants;

	private final StateEventRepository stateEvents;

	private final ConversationLifecycle lifecycle;

	private final OutboxAppender outbox;

	private final ObjectMapper mapper;

	private final TenantDirectory directory;

	private final Clock clock;

	private final int inboxPageMax;

	ConversationService(TransactionTemplate tx, ConversationAccess access, ConversationRepository conversations,
			ParticipantRepository participants, StateEventRepository stateEvents, ConversationLifecycle lifecycle,
			OutboxAppender outbox, ObjectMapper mapper, TenantDirectory directory, Clock clock,
			@Value("${app.limits.inbox-page-max}") int inboxPageMax) {
		this.tx = tx;
		this.access = access;
		this.conversations = conversations;
		this.participants = participants;
		this.stateEvents = stateEvents;
		this.lifecycle = lifecycle;
		this.outbox = outbox;
		this.mapper = mapper;
		this.directory = directory;
		this.clock = clock;
		this.inboxPageMax = inboxPageMax;
	}

	/** Result of start/resume: the conversation and whether this call created it. */
	public record StartResult(ConversationView conversation, boolean created) {
	}

	/**
	 * Returns the contact's latest non-archived conversation of the inbox (any status) or creates one; the partial unique
	 * index makes concurrent starts create exactly one (D11).
	 */
	public StartResult startOrResume(Caller caller) {
		if (!caller.isContact() || caller.inboxId() == null) {
			throw new ConversationAccessDeniedException();
		}
		return this.tx.execute((status) -> {
			Inbox inbox = this.directory.inbox(caller.tenantId(), caller.inboxId())
				.orElseThrow(ConversationNotFoundException::new);
			Optional<ConversationRow> existing = this.conversations.findLatestNonArchived(caller.tenantId(), inbox.id(),
					caller.contactId());
			if (existing.isPresent()) {
				return new StartResult(this.access.view(caller, existing.get().id()), false);
			}
			Optional<UUID> inserted = this.conversations.insertActiveConversation(caller.tenantId(), inbox.id(),
					caller.contactId(), OffsetDateTime.now(this.clock));
			UUID conversationId = inserted.orElseGet(() -> this.conversations
				.findLatestNonArchived(caller.tenantId(), inbox.id(), caller.contactId())
				.map(ConversationRow::id)
				.orElseThrow(() -> new IllegalStateException("conversation could neither be created nor read back")));
			if (inserted.isPresent()) {
				this.participants.ensure(conversationId, caller.tenantId(), caller.type().name(), caller.principalId());
			}
			ConversationView view = this.access.view(caller, conversationId);
			if (inserted.isPresent()) {
				this.outbox.append(caller.tenantId(), "conversation", conversationId.toString(), "conversation.created",
						List.of(Channels.inbox(caller.tenantId(), inbox.id())),
						EventPayloads.conversationCreated(view));
			}
			return new StartResult(view, inserted.isPresent());
		});
	}

	public ConversationView get(Caller caller, UUID conversationId) {
		this.access.requireReadable(caller, conversationId);
		return this.access.view(caller, conversationId);
	}

	/**
	 * Inbox page for an agent: {@code view} is {@code queue} (REQUESTED, QUEUED, FAILED, REOPEN_PENDING),
	 * {@code mine} (assigned to the caller) or {@code all}; {@code archived} selects the archive marker and the opaque
	 * cursor walks the page sequence without gaps or duplicates.
	 */
	public InboxPage listForAgent(Caller caller, UUID inboxId, String view, boolean archived, String cursor, int limit) {
		if (!caller.isAgent()) {
			throw new ConversationAccessDeniedException();
		}
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be positive");
		}
		String normalizedView = (view == null || view.isBlank()) ? "all" : view;
		if (!VIEWS.contains(normalizedView)) {
			throw new IllegalArgumentException("view must be queue, mine or all");
		}
		this.directory.inbox(caller.tenantId(), inboxId).orElseThrow(ConversationNotFoundException::new);
		InboxCursor keyset = (cursor == null || cursor.isBlank()) ? null
				: Cursors.decode(cursor).orElseThrow(() -> new IllegalArgumentException("malformed cursor"));
		int pageSize = Math.min(limit, this.inboxPageMax);
		List<ConversationView> rows = this.conversations.listForInbox(caller.tenantId(), inboxId, normalizedView,
				archived, keyset, pageSize + 1, caller);
		boolean hasMore = rows.size() > pageSize;
		List<ConversationView> items = hasMore ? List.copyOf(rows.subList(0, pageSize)) : List.copyOf(rows);
		String nextCursor = null;
		if (hasMore) {
			ConversationView last = items.get(items.size() - 1);
			OffsetDateTime activity = (last.lastMessageAt() != null) ? last.lastMessageAt() : last.createdAt();
			nextCursor = Cursors.encode(activity, last.id());
		}
		return new InboxPage(items, nextCursor);
	}

	public ConversationView resolve(Caller caller, UUID conversationId, CommandRequest request) {
		return command(caller, conversationId, ConversationLifecycle.Command.RESOLVE, request);
	}

	public ConversationView reopen(Caller caller, UUID conversationId, CommandRequest request) {
		return command(caller, conversationId, ConversationLifecycle.Command.REOPEN, request);
	}

	public ConversationView markPending(Caller caller, UUID conversationId, CommandRequest request) {
		return command(caller, conversationId, ConversationLifecycle.Command.MARK_PENDING, request);
	}

	public ConversationView archive(Caller caller, UUID conversationId, CommandRequest request) {
		return command(caller, conversationId, ConversationLifecycle.Command.ARCHIVE, request);
	}

	public ConversationView unarchive(Caller caller, UUID conversationId, CommandRequest request) {
		return command(caller, conversationId, ConversationLifecycle.Command.UNARCHIVE, request);
	}

	private ConversationView command(Caller caller, UUID conversationId, ConversationLifecycle.Command command,
			CommandRequest request) {
		if (!caller.isAgent()) {
			throw new ConversationAccessDeniedException();
		}
		Long expectedVersion = (request != null) ? request.expectedVersion() : null;
		String reason = (request != null) ? request.reason() : null;
		return this.tx.execute((status) -> {
			ConversationRow row = this.access.lockForWrite(caller, conversationId);
			// 1. A satisfied command is a 200 no-op even when expectedVersion is stale (safe client retries).
			if (this.lifecycle.isSatisfied(row.status(), row.archived(), command)) {
				return this.access.view(caller, conversationId);
			}
			// 2. Optimistic check before the transition table is consulted.
			if (expectedVersion != null && expectedVersion != row.version()) {
				throw new ConversationConflictException("STALE_VERSION");
			}
			// 3. Purged conversations are always archived and resolved: unarchiving one is not a transition (R-P8).
			if (command == ConversationLifecycle.Command.UNARCHIVE && row.purged()) {
				throw new ConversationConflictException("INVALID_TRANSITION");
			}
			// 4. The transition table.
			ConversationLifecycle.Outcome outcome = this.lifecycle.apply(row.status(), row.archived(), command);
			if (outcome.noOp()) {
				return this.access.view(caller, conversationId);
			}
			AssignmentState target = AssignmentTransitions.target(row.assignmentState(), outcome.assignmentEffect());
			String archivedBy = (command == ConversationLifecycle.Command.ARCHIVE) ? caller.principalId() : null;
			String archiveReason = (command == ConversationLifecycle.Command.ARCHIVE) ? reason : null;
			ConversationRepository.StateWrite write = new ConversationRepository.StateWrite(row.id(), row.tenantId(),
					row.version(), outcome.to(), OffsetDateTime.now(this.clock), outcome.resolvedNow(),
					outcome.archived(), archivedBy, archiveReason, target,
					AssignmentTransitions.movingOutOfFailed(row.assignmentState()));
			int updated;
			try {
				updated = this.conversations.updateState(write);
			}
			catch (org.springframework.dao.DataIntegrityViolationException ex) {
				// A reopen/unarchive (or a contact message that reopens) that would create a second active conversation.
				throw new ConversationConflictException("CONVERSATION_ACTIVE_EXISTS");
			}
			if (updated == 0) {
				throw new ConversationConflictException("STALE_VERSION");
			}
			this.stateEvents.insert(conversationId, row.tenantId(), outcome.stateEventType(), caller.type().name(),
					caller.principalId(), row.status().name(), outcome.to().name(), details(outcome, row, reason));
			ConversationView view = this.access.view(caller, conversationId);
			this.outbox.append(row.tenantId(), "conversation", conversationId.toString(), "conversation.updated",
					List.of(Channels.conversation(conversationId), Channels.inbox(row.tenantId(), row.inboxId())),
					EventPayloads.conversationUpdated(view, outcome.change()));
			return view;
		});
	}

	/**
	 * State-event details: the free-text reason when given (the purge clears it, R-P31 M10) and, for a resolve, the
	 * conversation's {@code last_sequence} so race tests can compare a message with the resolve by sequence (R-P31 M7).
	 */
	private String details(ConversationLifecycle.Outcome outcome, ConversationRow row, String reason) {
		Map<String, Object> details = new LinkedHashMap<>();
		if (outcome.change() == ConversationChange.RESOLVED) {
			details.put("lastSequence", row.lastSequence());
		}
		if (reason != null && !reason.isBlank()) {
			details.put("reason", reason);
		}
		return details.isEmpty() ? null : this.mapper.writeValueAsString(details);
	}

}
