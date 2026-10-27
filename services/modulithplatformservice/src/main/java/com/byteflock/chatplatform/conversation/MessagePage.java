package com.byteflock.chatplatform.conversation;

import java.util.List;

/**
 * One history page, ascending by {@code sequence}.
 *
 * @param items the page
 * @param hasMore whether older messages (for {@code before}) or newer messages exist outside this page
 */
public record MessagePage(List<MessageView> items, boolean hasMore) {
}
