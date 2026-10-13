package com.open.spring.mvc.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.S3uploads.S3FileHandler;

// Replies (replyToId) and threads (threadId) on group chat messages, stored through
// S3FileHandler's local-disk fallback so the JSONL round trip is real.
class GroupChatServiceThreadTest {

    private static final String GROUP = "csa-threads-test";

    @TempDir
    Path tempDir;

    private GroupChatService service;

    @BeforeEach
    void setUp() {
        S3FileHandler storage = new S3FileHandler();
        ReflectionTestUtils.setField(storage, "bucketName", "my-bucket");
        ReflectionTestUtils.setField(storage, "accessKey", "");
        ReflectionTestUtils.setField(storage, "secretKey", "");
        ReflectionTestUtils.setField(storage, "region", "us-east-1");
        ReflectionTestUtils.setField(storage, "localFallbackDir", tempDir.toString());
        storage.init();
        service = new GroupChatService(storage, new ObjectMapper());
    }

    private String post(String sender, String text, String replyToId, String threadId, boolean alsoInChannel) {
        GroupChatMessage message = new GroupChatMessage(sender, text, "2026-10-13T17:00:00Z", null);
        message.setReplyToId(replyToId);
        message.setThreadId(threadId);
        message.setAlsoInChannel(alsoInChannel);
        service.addMessage(GROUP, message);
        return message.getId();
    }

    private String post(String sender, String text) {
        return post(sender, text, null, null, false);
    }

    private GroupChatMessage stored(String id) {
        return service.getMessages(GROUP).stream()
                .filter(m -> id.equals(m.getId()))
                .findFirst()
                .orElse(null);
    }

    @Test
    void repliesAndThreadRepliesKeepTheirLinks() {
        String root = post("Teacher", "FRQ practice is posted");
        String question = post("Ava", "One per group?", null, root, false);
        String answer = post("Teacher", "Yes, one per group", question, root, true);
        String quote = post("Leo", "Thanks!", root, null, false);

        assertEquals(root, stored(question).getThreadId());
        assertFalse(stored(question).isAlsoInChannel());

        GroupChatMessage stored = stored(answer);
        assertEquals(question, stored.getReplyToId());
        assertEquals(root, stored.getThreadId());
        assertTrue(stored.isAlsoInChannel());

        assertEquals(root, stored(quote).getReplyToId());
        assertNull(stored(quote).getThreadId());
    }

    @Test
    void rejectsLinksToMessagesThatDontExist() {
        post("Teacher", "Hello");

        assertThrows(IllegalArgumentException.class, () -> post("Ava", "Hi", "no-such-id", null, false));
        assertThrows(IllegalArgumentException.class, () -> post("Ava", "Hi", null, "no-such-id", false));
        assertEquals(1, service.getMessages(GROUP).size());
    }

    @Test
    void threadsDontNest() {
        String root = post("Teacher", "Lab is open at lunch");
        String reply = post("Ava", "Which room?", null, root, false);

        assertThrows(IllegalArgumentException.class, () -> post("Leo", "Same question", null, reply, false));
    }

    @Test
    void blankLinksCountAsNoLink() {
        String id = post("Ava", "Plain message", "", " ", true);

        GroupChatMessage stored = stored(id);
        assertNull(stored.getReplyToId());
        assertNull(stored.getThreadId());
        assertFalse(stored.isAlsoInChannel());
    }

    @Test
    void deletingAMessageWithAThreadKeepsItAsDeleted() {
        String root = post("Teacher", "Quiz moved to Friday");
        String reply = post("Ava", "Thanks", null, root, false);

        service.deleteMessage(GROUP, root, "Teacher");

        GroupChatMessage tombstone = stored(root);
        assertTrue(tombstone.isDeleted());
        assertEquals("", tombstone.getMessage());
        assertEquals(root, stored(reply).getThreadId());
    }

    @Test
    void deletingAMessageWithoutAThreadRemovesIt() {
        String root = post("Teacher", "Typo");
        String other = post("Ava", "Hi");

        service.deleteMessage(GROUP, root, "Teacher");

        assertNull(stored(root));
        assertEquals(List.of(other), service.getMessages(GROUP).stream().map(GroupChatMessage::getId).toList());
    }

    @Test
    void deletedMessagesCantBeEdited() {
        String root = post("Teacher", "Original");
        post("Ava", "Reply", null, root, false);
        service.deleteMessage(GROUP, root, "Teacher");

        assertFalse(service.editMessage(GROUP, root, "Back again", "Teacher"));
        assertEquals("", stored(root).getMessage());
    }

    @Test
    void onlyTheSenderCanEditOrDelete() {
        String root = post("Teacher", "Announcement");

        assertThrows(SecurityException.class, () -> service.editMessage(GROUP, root, "Hacked", "Ava"));
        assertThrows(SecurityException.class, () -> service.deleteMessage(GROUP, root, "Ava"));
        assertEquals("Announcement", stored(root).getMessage());
    }
}
