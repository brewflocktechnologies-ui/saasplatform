package com.byteflock.chatplatform.conversation;

import java.util.List;

/** One inbox page plus the opaque cursor of the next page ({@code null} when this is the last page). */
public record InboxPage(List<ConversationView> items, String nextCursor) {
}
