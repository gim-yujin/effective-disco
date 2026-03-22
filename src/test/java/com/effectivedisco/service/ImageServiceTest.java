package com.effectivedisco.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageServiceTest {

    @TempDir
    Path tempDir;

    ImageService imageService;

    @BeforeEach
    void setUp() throws IOException {
        imageService = new ImageService(tempDir.toString());
    }

    @Test
    void store_validJpeg_returnsServingUrl() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "image", "photo.jpg", "image/jpeg", fakeBytes(1024));

        String url = imageService.store(file);

        assertThat(url).startsWith("/uploads/images/");
        assertThat(url).endsWith(".jpg");
    }

    @Test
    void store_validPng_returnsServingUrl() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "image", "photo.png", "image/png", fakeBytes(512));

        String url = imageService.store(file);

        assertThat(url).startsWith("/uploads/images/");
        assertThat(url).endsWith(".png");
    }

    @Test
    void store_emptyFile_returnsNull() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "image", "empty.jpg", "image/jpeg", new byte[0]);

        assertThat(imageService.store(file)).isNull();
    }

    @Test
    void store_nullFile_returnsNull() throws IOException {
        assertThat(imageService.store(null)).isNull();
    }

    @Test
    void store_tooLargeFile_throwsIllegalArgumentException() {
        // 5 MB + 1 byte exceeds the limit
        byte[] bigData = fakeBytes(5 * 1024 * 1024 + 1);
        MockMultipartFile file = new MockMultipartFile(
                "image", "big.jpg", "image/jpeg", bigData);

        assertThatThrownBy(() -> imageService.store(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5MB 이하");
    }

    @Test
    void store_invalidMimeType_throwsIllegalArgumentException() {
        MockMultipartFile file = new MockMultipartFile(
                "image", "script.pdf", "application/pdf", fakeBytes(256));

        assertThatThrownBy(() -> imageService.store(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JPEG, PNG, GIF, WebP");
    }

    @Test
    void store_textMimeType_throwsIllegalArgumentException() {
        MockMultipartFile file = new MockMultipartFile(
                "image", "file.txt", "text/plain", fakeBytes(64));

        assertThatThrownBy(() -> imageService.store(file))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── helpers ───────────────────────────────────────────────

    private byte[] fakeBytes(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) data[i] = (byte) (i % 256);
        return data;
    }
}
