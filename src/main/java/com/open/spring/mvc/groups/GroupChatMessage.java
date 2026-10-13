package com.open.spring.mvc.groups;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupChatMessage {
    private String id;
    private String name;
    private String message;
    private String date;
    private String image;
    /** Set once on the first edit; stays true even if edited again. */
    private boolean edited;
    /** A deleted message that still has thread replies stays, with an empty message, so its thread survives. */
    private boolean deleted;
    /** Id of the message this one quotes (a reply in the main chat), or null. */
    private String replyToId;
    /** Id of the top-level message whose thread this belongs to, or null for the main chat. */
    private String threadId;
    /** A thread reply that is also shown in the main chat. Only meaningful with threadId. */
    private boolean alsoInChannel;

    public GroupChatMessage(String name, String message, String date, String image) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.message = message;
        this.date = date;
        this.image = image;
    }
}
