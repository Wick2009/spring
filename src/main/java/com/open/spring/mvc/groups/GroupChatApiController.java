package com.open.spring.mvc.groups;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@RestController
@RequestMapping("/api/groups/chat")
@CrossOrigin
public class GroupChatApiController {

    private final GroupChatService groupChatService;
    private final GroupChatRealtimeService realtimeService;
    private final GroupsJpaRepository groupsRepository;
    private final PersonJpaRepository personRepository;

    public GroupChatApiController(
            GroupChatService groupChatService,
            GroupChatRealtimeService realtimeService,
            GroupsJpaRepository groupsRepository,
            PersonJpaRepository personRepository) {
        this.groupChatService = groupChatService;
        this.realtimeService = realtimeService;
        this.groupsRepository = groupsRepository;
        this.personRepository = personRepository;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileUploadRequest {
        private String filename;
        private String base64Data;
    }

    // --- Auth helpers ---
    // Only wired into editMessage/deleteMessage below (see their ownership checks) -- every
    // other endpoint here still has no auth enforcement, matching this controller's existing
    // (pre-existing, not changed here) permissive posture. isMember() stays commented out:
    // unused once edit/delete moved to a sender-identity check instead of membership.
    private String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        String name = auth.getName();
        if (name == null || name.isBlank() || "anonymousUser".equals(name)) {
            return null;
        }
        return name;
    }

    // GroupChatMessage only stores the sender's display name (no Person link), so the
    // ownership check needs the authenticated uid resolved to that same display name.
    private String resolveCurrentDisplayName() {
        String uid = getCurrentUsername();
        if (uid == null) {
            return null;
        }
        Person person = personRepository.findByUid(uid);
        return person == null ? null : person.getName();
    }

    // private boolean isMember(Groups group, String uid) {
    //     return group.getGroupMembers().stream()
    //             .anyMatch(member -> uid.equals(member.getUid()));
    // }

    @GetMapping("/analytics/{personId}")
    public ResponseEntity<?> getUserAnalytics(@PathVariable Long personId) {
        Optional<Person> personOpt = personRepository.findById(personId);
        if (personOpt.isEmpty()) {
            return new ResponseEntity<>("Person not found", HttpStatus.NOT_FOUND);
        }

        Person person = personOpt.get();
        List<Groups> groups = groupsRepository.findGroupsByPersonId(personId);

        Map<String, Object> analytics = groupChatService.getUserAnalytics(person.getName(), groups);
        analytics.put("personId", personId);
        analytics.put("personName", person.getName());

        return new ResponseEntity<>(analytics, HttpStatus.OK);
    }

    @GetMapping("/{groupId}/messages")
    public ResponseEntity<?> getMessages(@PathVariable Long groupId) {
        Optional<Groups> groupOpt = groupsRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        // String currentUsername = getCurrentUsername();
        // if (currentUsername == null) {
        //     return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        // }
        // if (!isMember(groupOpt.get(), currentUsername)) {
        //     return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        // }

        String groupName = groupOpt.get().getName();
        List<GroupChatMessage> messages = groupChatService.getMessages(groupName);
        return new ResponseEntity<>(messages, HttpStatus.OK);
    }

    @PostMapping("/{groupId}/messages")
    public ResponseEntity<?> postMessage(
            @PathVariable Long groupId,
            @RequestBody GroupChatMessage message) {
        Optional<Groups> groupOpt = groupsRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        // String currentUsername = getCurrentUsername();
        // if (currentUsername == null) {
        //     return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        // }
        // if (!isMember(groupOpt.get(), currentUsername)) {
        //     return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        // }

        if (message == null || message.getName() == null || message.getMessage() == null) {
            return new ResponseEntity<>("name and message are required", HttpStatus.BAD_REQUEST);
        }

        try {
            realtimeService.publishMessage(groupId, message.getName(), message.getMessage(), message.getImage(),
                    message.getReplyToId(), message.getThreadId(), message.isAlsoInChannel());
        } catch (RuntimeException ex) {
            return new ResponseEntity<>(ex.getMessage(), HttpStatus.BAD_REQUEST);
        }

        String groupName = groupOpt.get().getName();
        List<GroupChatMessage> updated = groupChatService.getMessages(groupName);
        return new ResponseEntity<>(updated, HttpStatus.OK);
    }

    @PatchMapping("/{groupId}/messages/{messageId}")
    public ResponseEntity<?> editMessage(
            @PathVariable Long groupId,
            @PathVariable String messageId,
            @RequestBody GroupChatMessage request) {
        Optional<Groups> groupOpt = groupsRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        String requesterDisplayName = resolveCurrentDisplayName();
        if (requesterDisplayName == null) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }

        if (request == null || request.getMessage() == null || request.getMessage().isBlank()) {
            return new ResponseEntity<>("message is required", HttpStatus.BAD_REQUEST);
        }

        boolean updated;
        try {
            updated = realtimeService.editMessage(groupId, messageId, request.getMessage(), requesterDisplayName);
        } catch (SecurityException ex) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        } catch (RuntimeException ex) {
            return new ResponseEntity<>(ex.getMessage(), HttpStatus.BAD_REQUEST);
        }
        if (!updated) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        String groupName = groupOpt.get().getName();
        List<GroupChatMessage> messages = groupChatService.getMessages(groupName);
        return new ResponseEntity<>(messages, HttpStatus.OK);
    }

    @DeleteMapping("/{groupId}/messages/{messageId}")
    public ResponseEntity<?> deleteMessage(
            @PathVariable Long groupId,
            @PathVariable String messageId) {
        Optional<Groups> groupOpt = groupsRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        String requesterDisplayName = resolveCurrentDisplayName();
        if (requesterDisplayName == null) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }

        try {
            realtimeService.deleteMessage(groupId, messageId, requesterDisplayName);
        } catch (SecurityException ex) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        } catch (RuntimeException ex) {
            return new ResponseEntity<>(ex.getMessage(), HttpStatus.BAD_REQUEST);
        }

        return new ResponseEntity<>(HttpStatus.OK);
    }

    @GetMapping("/{groupId}/files")
    public ResponseEntity<?> getSharedFiles(@PathVariable Long groupId) {
        Optional<Groups> groupOpt = groupsRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        // String currentUsername = getCurrentUsername();
        // if (currentUsername == null) {
        //     return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        // }
        // if (!isMember(groupOpt.get(), currentUsername)) {
        //     return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        // }

        String groupName = groupOpt.get().getName();
        List<Map<String, String>> files = groupChatService.listSharedFiles(groupName);
        return new ResponseEntity<>(files, HttpStatus.OK);
    }

    @PostMapping("/{groupId}/files")
    public ResponseEntity<?> uploadSharedFile(
            @PathVariable Long groupId,
            @RequestBody FileUploadRequest request) {
        Optional<Groups> groupOpt = groupsRepository.findById(groupId);
        if (groupOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        // String currentUsername = getCurrentUsername();
        // if (currentUsername == null) {
        //     return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        // }
        // if (!isMember(groupOpt.get(), currentUsername)) {
        //     return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        // }

        if (request == null || request.getFilename() == null || request.getBase64Data() == null) {
            return new ResponseEntity<>("filename and base64Data are required", HttpStatus.BAD_REQUEST);
        }

        try {
            realtimeService.publishFile(groupId, null, request.getFilename(), request.getBase64Data());
            Map<String, String> response = new HashMap<>();
            response.put("message", "File uploaded successfully");
            response.put("filename", request.getFilename());
            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (RuntimeException ex) {
            return new ResponseEntity<>("Upload failed", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
