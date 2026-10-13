package com.open.spring.mvc.groups;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupChatEvent {
    private String context;
    private Long groupId;
    private String messageId;
    private String sender;
    private String message;
    private String image;
    private String filename;
    private String base64Data;
    private String date;
    private String sessionId;
    private String error;
    private List<String> participants;
    private boolean edited;
    /** See {@link GroupChatMessage} for these three. */
    private String replyToId;
    private String threadId;
    private boolean alsoInChannel;
}
