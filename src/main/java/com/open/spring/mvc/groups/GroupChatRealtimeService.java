package com.open.spring.mvc.groups;

import java.time.Instant;
import java.util.Optional;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Service to handle the real-time publishing of group chat messages and presence events.
 * It uses {@link SimpMessagingTemplate} to broadcast messages to corresponding specific group topics.
 */
@Service
@RequiredArgsConstructor
public class GroupChatRealtimeService {

    public static final String GROUP_TOPIC_PREFIX = "/topic/group/";

    private final GroupChatService groupChatService;
    private final GroupsJpaRepository groupsRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public GroupChatEvent publishMessage(Long groupId, String sender, String message, String image) {
        return publishMessage(groupId, sender, message, image, null, null, false);
    }

    /**
     * Same as {@link #publishMessage(Long, String, String, String)} for a reply ({@code replyToId})
     * and/or a thread reply ({@code threadId}, optionally {@code alsoInChannel}). Throws
     * {@link IllegalArgumentException} if a link doesn't point at a message in this group.
     */
    public GroupChatEvent publishMessage(Long groupId, String sender, String message, String image,
                                         String replyToId, String threadId, boolean alsoInChannel) {
        Groups group = getGroupOrThrow(groupId);

        String date = Instant.now().toString();
        GroupChatMessage persisted = new GroupChatMessage(sender, message, date, image);
        persisted.setReplyToId(replyToId);
        persisted.setThreadId(threadId);
        persisted.setAlsoInChannel(alsoInChannel);
        groupChatService.addMessage(group.getName(), persisted);

        GroupChatEvent event = GroupChatEvent.builder()
                .context("sendMessageServer")
                .groupId(groupId)
                .messageId(persisted.getId())
                .sender(sender)
                .message(message)
                .image(image)
                .date(date)
                .replyToId(persisted.getReplyToId())
                .threadId(persisted.getThreadId())
                .alsoInChannel(persisted.isAlsoInChannel())
                .build();

        broadcastToGroup(groupId, event);
        return event;
    }

    /**
     * Returns whether the message existed (and was broadcast as edited). Throws
     * {@link SecurityException} if {@code requesterDisplayName} didn't send it --
     * see {@link GroupChatService#editMessage} for why a display name, not a uid.
     */
    public boolean editMessage(Long groupId, String messageId, String newMessage, String requesterDisplayName) {
        Groups group = getGroupOrThrow(groupId);
        boolean updated = groupChatService.editMessage(group.getName(), messageId, newMessage, requesterDisplayName);
        if (!updated) {
            return false;
        }

        GroupChatEvent event = GroupChatEvent.builder()
                .context("editMessageServer")
                .groupId(groupId)
                .messageId(messageId)
                .message(newMessage)
                .edited(true)
                .date(Instant.now().toString())
                .build();

        broadcastToGroup(groupId, event);
        return true;
    }

    /** Throws {@link SecurityException} if {@code requesterDisplayName} didn't send it. */
    public void deleteMessage(Long groupId, String messageId, String requesterDisplayName) {
        Groups group = getGroupOrThrow(groupId);
        groupChatService.deleteMessage(group.getName(), messageId, requesterDisplayName);

        GroupChatEvent event = GroupChatEvent.builder()
                .context("deleteMessageServer")
                .groupId(groupId)
                .messageId(messageId)
                .date(Instant.now().toString())
                .build();

        broadcastToGroup(groupId, event);
    }

    public GroupChatEvent publishFile(Long groupId, String sender, String filename, String base64Data) {
        Groups group = getGroupOrThrow(groupId);

        String uploadResult = groupChatService.uploadSharedFile(group.getName(), filename, base64Data);
        if (uploadResult == null) {
            throw new IllegalStateException("Upload failed");
        }

        GroupChatEvent event = GroupChatEvent.builder()
                .context("sendFileServer")
                .groupId(groupId)
                .sender(sender)
                .filename(filename)
                .base64Data(base64Data)
                .date(Instant.now().toString())
                .build();

        broadcastToGroup(groupId, event);
        return event;
    }

    public void publishPresence(Long groupId, String context, String sender, java.util.List<String> participants) {
        GroupChatEvent event = GroupChatEvent.builder()
                .context(context)
                .groupId(groupId)
                .sender(sender)
                .participants(participants)
                .date(Instant.now().toString())
                .build();
        broadcastToGroup(groupId, event);
    }

    public void publishTyping(Long groupId, String sender, boolean typing) {
        GroupChatEvent event = GroupChatEvent.builder()
                .context(typing ? "typingStartServer" : "typingStopServer")
                .groupId(groupId)
                .sender(sender)
                .date(Instant.now().toString())
                .build();
        broadcastToGroup(groupId, event);
    }

    public void publishError(Long groupId, String sender, String errorMessage) {
        GroupChatEvent event = GroupChatEvent.builder()
                .context("errorServer")
                .groupId(groupId)
                .sender(sender)
                .error(errorMessage)
                .date(Instant.now().toString())
                .build();
        broadcastToGroup(groupId, event);
    }

    private void broadcastToGroup(Long groupId, GroupChatEvent event) {
        messagingTemplate.convertAndSend(GROUP_TOPIC_PREFIX + groupId, event);
    }

    private Groups getGroupOrThrow(Long groupId) {
        Optional<Groups> groupOpt = groupsRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            throw new IllegalArgumentException("Group not found");
        }
        return groupOpt.get();
    }
}
