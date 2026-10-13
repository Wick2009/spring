package com.open.spring.mvc.S3uploads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class S3FileHandlerLocalFallbackTest {

    @TempDir
    Path tempDir;

    private S3FileHandler handlerWithoutAws(String localFallbackDir) {
        S3FileHandler handler = new S3FileHandler();
        ReflectionTestUtils.setField(handler, "bucketName", "my-bucket");
        ReflectionTestUtils.setField(handler, "accessKey", "");
        ReflectionTestUtils.setField(handler, "secretKey", "");
        ReflectionTestUtils.setField(handler, "region", "us-east-1");
        ReflectionTestUtils.setField(handler, "localFallbackDir", localFallbackDir);
        handler.init();
        return handler;
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void storesAndReadsFilesUnderTheFallbackFolder() {
        S3FileHandler handler = handlerWithoutAws(tempDir.toString());

        assertEquals("notes.txt", handler.uploadFile(base64("hello"), "notes.txt", "group-a"));
        assertTrue(Files.isRegularFile(tempDir.resolve("group-a/notes.txt")));
        assertEquals(base64("hello"), handler.decodeFile("group-a", "notes.txt"));
        assertTrue(handler.fileExists("group-a", "notes.txt"));

        assertNull(handler.decodeFile("group-a", "missing.txt"));
        assertFalse(handler.fileExists("group-a", "missing.txt"));
    }

    @Test
    void folderPlaceholdersBehaveLikeS3() {
        S3FileHandler handler = handlerWithoutAws(tempDir.toString());

        assertEquals("shared-files/", handler.uploadFile("", "shared-files/", "group-a"));
        assertTrue(handler.fileExists("group-a", "shared-files/"));
        assertEquals(List.of(), handler.listFiles("group-a/shared-files/"));

        handler.uploadFile(base64("png"), "shared-files/a.png", "group-a");
        assertEquals(List.of("group-a/shared-files/a.png"), handler.listFiles("group-a/shared-files/"));
    }

    @Test
    void deleteFilesRemovesOnlyThatOwner() {
        S3FileHandler handler = handlerWithoutAws(tempDir.toString());
        handler.uploadFile(base64("a"), "one.txt", "group-a");
        handler.uploadFile(base64("b"), "one.txt", "group-b");

        assertTrue(handler.deleteFiles("group-a"));

        assertFalse(handler.fileExists("group-a", "one.txt"));
        assertTrue(handler.fileExists("group-b", "one.txt"));
    }

    @Test
    void refusesKeysThatLeaveTheFolder() {
        Path root = tempDir.resolve("uploads");
        S3FileHandler handler = handlerWithoutAws(root.toString());

        assertNull(handler.uploadFile(base64("x"), "../../escape.txt", "group-a"));
        assertFalse(Files.exists(tempDir.resolve("escape.txt")));
        assertNull(handler.decodeFile("..", "secret.txt"));
        assertFalse(handler.deleteFiles(".."));

        handler.uploadFile(base64("keep"), "keep.txt", "group-a");
        assertFalse(handler.deleteFiles("."));
        assertTrue(handler.fileExists("group-a", "keep.txt"));
    }

    @Test
    void emptyFallbackFolderKeepsTheOldBehavior() {
        S3FileHandler handler = handlerWithoutAws("");

        assertNull(handler.uploadFile(base64("x"), "notes.txt", "group-a"));
        assertNull(handler.decodeFile("group-a", "notes.txt"));
        assertFalse(handler.fileExists("group-a", "notes.txt"));
        assertEquals(List.of(), handler.listFiles("group-a/"));
    }
}
