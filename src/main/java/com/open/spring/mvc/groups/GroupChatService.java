package com.open.spring.mvc.groups;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.S3uploads.S3FileHandler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class GroupChatService {
    private static final String MESSAGES_FILE = "messages-images/messages.jsonl";
    private static final String SHARED_FILES_PREFIX = "shared-files/";

    private final S3FileHandler s3FileHandler;
    private final ObjectMapper objectMapper;

    public void initGroupStorage(String groupName) {
        // Create empty messages.jsonl file
        String emptyBase64 = Base64.getEncoder().encodeToString(new byte[0]);
        s3FileHandler.uploadFile(emptyBase64, MESSAGES_FILE, groupName);

        // Create shared-files/ folder (S3 uses a zero-byte placeholder to represent an empty folder)
        s3FileHandler.uploadFile(emptyBase64, SHARED_FILES_PREFIX, groupName);
    }

    public void ensureGroupStorageExists(String groupName) {
        // Check if messages.jsonl exists, if not, create it
        if (!s3FileHandler.fileExists(groupName, MESSAGES_FILE)) {
            String emptyBase64 = Base64.getEncoder().encodeToString(new byte[0]);
            s3FileHandler.uploadFile(emptyBase64, MESSAGES_FILE, groupName);
        }

        // Check if shared-files/ folder exists, if not, create it
        if (!s3FileHandler.fileExists(groupName, SHARED_FILES_PREFIX)) {
            String emptyBase64 = Base64.getEncoder().encodeToString(new byte[0]);
            s3FileHandler.uploadFile(emptyBase64, SHARED_FILES_PREFIX, groupName);
        }
    }

    public List<GroupChatMessage> getMessages(String groupName) {
        String base64Data = s3FileHandler.decodeFile(groupName, MESSAGES_FILE);
        if (base64Data == null || base64Data.isBlank()) {
            return new ArrayList<>();
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Data);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid base64 data for group {} messages.", groupName, e);
            return new ArrayList<>();
        }

        String jsonl = new String(decoded, StandardCharsets.UTF_8);
        if (jsonl.isBlank()) {
            return new ArrayList<>();
        }

        List<GroupChatMessage> messages = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(jsonl))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    GroupChatMessage msg = objectMapper.readValue(line, GroupChatMessage.class);
                    messages.add(msg);
                } catch (Exception e) {
                    log.warn("Skipping invalid message line for group {}: {}", groupName, line, e);
                }
            }
        } catch (Exception e) {
            log.warn("Failed reading messages for group {}", groupName, e);
        }

        return messages;
    }

    /**
     * Throws {@link IllegalArgumentException} if the message's reply or thread link
     * doesn't point at a message in this group (see {@link #checkLinks}).
     */
    public List<GroupChatMessage> addMessage(String groupName, GroupChatMessage message) {
        List<GroupChatMessage> messages = getMessages(groupName);
        checkLinks(messages, message);
        messages.add(message);
        saveMessages(groupName, messages);
        return messages;
    }

    /**
     * A reply must quote a message in this group, and a thread reply must hang off a
     * top-level message: threads don't nest. Blank ids count as no link, and
     * alsoInChannel is dropped when there's no thread.
     */
    private void checkLinks(List<GroupChatMessage> messages, GroupChatMessage message) {
        if (isBlank(message.getReplyToId())) {
            message.setReplyToId(null);
        } else if (findById(messages, message.getReplyToId()) == null) {
            throw new IllegalArgumentException("The message being replied to doesn't exist");
        }

        if (isBlank(message.getThreadId())) {
            message.setThreadId(null);
            message.setAlsoInChannel(false);
            return;
        }
        GroupChatMessage root = findById(messages, message.getThreadId());
        if (root == null) {
            throw new IllegalArgumentException("The thread doesn't exist");
        }
        if (root.getThreadId() != null) {
            throw new IllegalArgumentException("Threads can't be nested");
        }
    }

    /**
     * Returns whether a message with that id was found (and updated) in this group.
     * Throws {@link SecurityException} if the message exists but {@code requesterDisplayName}
     * didn't send it. Compared by display name, not uid -- a {@link GroupChatMessage} only
     * ever stores the sender's display name (there's no Person link, unlike direct messages),
     * since group chat predates/doesn't use Person-backed identity for its S3-stored history.
     */
    public boolean editMessage(String groupName, String messageId, String newMessage, String requesterDisplayName) {
        if (messageId == null) return false;
        List<GroupChatMessage> messages = getMessages(groupName);
        GroupChatMessage target = findById(messages, messageId);
        // A deleted message that was kept for its thread can't be brought back by editing.
        if (target == null || target.isDeleted()) {
            return false;
        }
        if (requesterDisplayName == null || !requesterDisplayName.equals(target.getName())) {
            throw new SecurityException("Not the message sender");
        }
        target.setMessage(newMessage);
        target.setEdited(true);
        saveMessages(groupName, messages);
        return true;
    }

    /**
     * Same ownership rule as {@link #editMessage}; throws {@link SecurityException} on mismatch.
     * A message with thread replies is kept as an empty, deleted message so the thread
     * survives; any other message is removed.
     */
    public void deleteMessage(String groupName, String messageId, String requesterDisplayName) {
        if (messageId == null) return;
        List<GroupChatMessage> messages = getMessages(groupName);

        GroupChatMessage target = findById(messages, messageId);
        if (target == null || target.isDeleted()) return;
        if (requesterDisplayName == null || !requesterDisplayName.equals(target.getName())) {
            throw new SecurityException("Not the message sender");
        }

        boolean hasThread = messages.stream().anyMatch(m -> messageId.equals(m.getThreadId()));
        if (hasThread) {
            target.setDeleted(true);
            target.setMessage("");
            target.setImage(null);
        } else {
            messages.remove(target);
        }
        saveMessages(groupName, messages);
    }

    private void saveMessages(String groupName, List<GroupChatMessage> messages) {
        String jsonl = messages.stream()
                .map(this::toJson)
                .collect(Collectors.joining("\n"));

        String base64Data = Base64.getEncoder()
                .encodeToString(jsonl.getBytes(StandardCharsets.UTF_8));

        s3FileHandler.uploadFile(base64Data, MESSAGES_FILE, groupName);
    }

    private static GroupChatMessage findById(List<GroupChatMessage> messages, String id) {
        return messages.stream()
                .filter(m -> id.equals(m.getId()))
                .findFirst()
                .orElse(null);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public List<Map<String, String>> listSharedFiles(String groupName) {
        String prefix = groupName + "/" + SHARED_FILES_PREFIX;
        List<String> keys = s3FileHandler.listFiles(prefix);

        List<String> filenames = keys.stream()
                .map(key -> key.substring(prefix.length()))
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toList());

        List<Map<String, String>> files = new ArrayList<>();
        for (String filename : filenames) {
            Map<String, String> fileEntry = new HashMap<>();
            fileEntry.put("filename", filename);
            String base64Data = s3FileHandler.decodeFile(groupName, SHARED_FILES_PREFIX + filename);
            fileEntry.put("base64Data", base64Data);
            files.add(fileEntry);
        }
        return files;
    }

    public String uploadSharedFile(String groupName, String filename, String base64Data) {
        return s3FileHandler.uploadFile(base64Data, SHARED_FILES_PREFIX + filename, groupName);
    }

    public String downloadSharedFile(String groupName, String filename) {
        return s3FileHandler.decodeFile(groupName, SHARED_FILES_PREFIX + filename);
    }

    public Map<String, Object> getUserAnalytics(String personName, List<Groups> groups) {
        Map<String, Object> analytics = new HashMap<>();
        List<Map<String, Object>> groupAnalyticsList = new ArrayList<>();

        int totalMessagesSent = 0;
        int totalMessagesWithImages = 0;
        int totalSharedFiles = 0;

        for (Groups group : groups) {
            String groupName = group.getName();
            List<GroupChatMessage> messages = getMessages(groupName);

            List<GroupChatMessage> userMessages = messages.stream()
                    .filter(m -> personName.equals(m.getName()))
                    .collect(Collectors.toList());

            int messagesSent = userMessages.size();
            int messagesWithImages = (int) userMessages.stream()
                    .filter(m -> m.getImage() != null && !m.getImage().isBlank())
                    .count();

            List<Map<String, String>> sharedFiles = listSharedFiles(groupName);
            int sharedFilesCount = sharedFiles.size();

            Map<String, Object> groupEntry = new HashMap<>();
            groupEntry.put("groupId", group.getId());
            groupEntry.put("groupName", groupName);
            groupEntry.put("messagesSent", messagesSent);
            groupEntry.put("messagesWithImages", messagesWithImages);
            groupEntry.put("sharedFilesCount", sharedFilesCount);
            groupAnalyticsList.add(groupEntry);

            totalMessagesSent += messagesSent;
            totalMessagesWithImages += messagesWithImages;
            totalSharedFiles += sharedFilesCount;
        }

        analytics.put("totalGroups", groups.size());
        analytics.put("totalMessagesSent", totalMessagesSent);
        analytics.put("totalMessagesWithImages", totalMessagesWithImages);
        analytics.put("totalSharedFiles", totalSharedFiles);
        analytics.put("groupAnalytics", groupAnalyticsList);

        return analytics;
    }

    private String toJson(GroupChatMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.warn("Failed to serialize message: {}", message, e);
            return "{}";
        }
    }
}
