package ru.Water_Tours.telegram;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which private chats are in the middle of typing a question.
 *
 * Bounded on purpose. A state expires by itself after {@link #TTL}, so a chat that opens the
 * question flow and walks away is not stuck in it, and the map is capped: past {@link #MAX_CHATS}
 * live states a new one is refused rather than added, so nobody can grow it by opening the flow
 * from many chats. Expired entries are pruned on every write.
 *
 * Only the chat id and a timestamp are kept here - never the text being typed.
 */
@Component
public class SupportChatStates {

    static final Duration TTL = Duration.ofMinutes(15);
    static final int MAX_CHATS = 500;
    static final Duration RATE_WINDOW = Duration.ofHours(1);
    static final int MAX_QUESTIONS_PER_WINDOW = 3;

    private final Clock clock;
    private final Map<Long, Instant> awaitingSince = new ConcurrentHashMap<>();
    private final Map<Long, Window> submissions = new ConcurrentHashMap<>();

    public SupportChatStates(Clock clock) {
        this.clock = clock;
    }

    /** @return false when the flow could not be opened because the store is at its cap. */
    public boolean startQuestion(long chatId) {
        Instant now = clock.instant();
        prune(now);
        if (!awaitingSince.containsKey(chatId) && awaitingSince.size() >= MAX_CHATS) {
            return false;
        }
        awaitingSince.put(chatId, now);
        return true;
    }

    public boolean isAwaitingQuestion(long chatId) {
        Instant startedAt = awaitingSince.get(chatId);
        if (startedAt == null) {
            return false;
        }
        if (startedAt.plus(TTL).isBefore(clock.instant())) {
            awaitingSince.remove(chatId);
            return false;
        }
        return true;
    }

    public void clear(long chatId) {
        awaitingSince.remove(chatId);
    }

    /**
     * Per-chat submission limit, the Telegram counterpart of the web form's rate limit. Counts
     * only questions that were actually accepted, and is bounded exactly like the state map.
     *
     * @return true when this chat has already used up its window and must wait
     */
    public boolean isRateLimited(long chatId) {
        Instant now = clock.instant();
        pruneSubmissions(now);
        Window window = submissions.get(chatId);
        if (window == null) {
            return submissions.size() >= MAX_CHATS;
        }
        if (window.startedAt.plus(RATE_WINDOW).isBefore(now)) {
            return false;
        }
        return window.count >= MAX_QUESTIONS_PER_WINDOW;
    }

    public void recordQuestion(long chatId) {
        Instant now = clock.instant();
        submissions.compute(chatId, (id, current) -> {
            if (current == null || current.startedAt.plus(RATE_WINDOW).isBefore(now)) {
                return new Window(now, 1);
            }
            return new Window(current.startedAt, current.count + 1);
        });
    }

    private void prune(Instant now) {
        for (Iterator<Map.Entry<Long, Instant>> it = awaitingSince.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().plus(TTL).isBefore(now)) {
                it.remove();
            }
        }
    }

    private void pruneSubmissions(Instant now) {
        for (Iterator<Map.Entry<Long, Window>> it = submissions.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().startedAt.plus(RATE_WINDOW).isBefore(now)) {
                it.remove();
            }
        }
    }

    int trackedChats() {
        return awaitingSince.size();
    }

    private record Window(Instant startedAt, int count) {
    }
}
