package com.effectivedisco.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 이미지 파일 저장 서비스.
 *
 * 파일은 app.upload-dir 경로(기본: uploads/images/)에 저장되고
 * WebConfig에서 /uploads/images/** 로 서빙된다.
 */
@Service
public class ImageService {

    private static final long MAX_BYTES   = 5 * 1024 * 1024; // 5 MB
    private static final String[] ALLOWED = {"image/jpeg", "image/png", "image/gif", "image/webp"};

    private final Path uploadDir;

    public ImageService(@Value("${app.upload-dir:uploads/images}") String dir) throws IOException {
        this.uploadDir = Paths.get(dir).toAbsolutePath().normalize();
        Files.createDirectories(this.uploadDir);
    }

    /**
     * 이미지를 저장하고 서빙 URL을 반환한다.
     *
     * @param file 업로드된 MultipartFile
     * @return 서빙 URL (예: /uploads/images/uuid.jpg)
     */
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("이미지 파일은 5MB 이하여야 합니다.");
        }
        String contentType = file.getContentType();
        boolean allowed = false;
        for (String t : ALLOWED) {
            if (t.equals(contentType)) { allowed = true; break; }
        }
        if (!allowed) {
            throw new IllegalArgumentException("JPEG, PNG, GIF, WebP 형식의 이미지만 업로드할 수 있습니다.");
        }

        String ext = getExtension(file.getOriginalFilename());
        String filename = UUID.randomUUID() + ext;
        Path dest = uploadDir.resolve(filename);

        try {
            Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("이미지 저장에 실패했습니다.", e);
        }

        return "/uploads/images/" + filename;
    }

    /**
     * 여러 이미지를 저장하고 서빙 URL 목록을 반환한다.
     * 비어 있는 파일은 자동으로 건너뛴다.
     *
     * @param files 업로드된 MultipartFile 목록 (null 또는 빈 파일 포함 가능)
     * @return 저장된 이미지의 서빙 URL 목록 (비어 있는 파일 제외)
     */
    public List<String> storeAll(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) return List.of();
        List<String> urls = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) {
                urls.add(store(file)); // 개별 유효성 검사(크기·형식)는 store()에서 수행
            }
        }
        return urls;
    }

    private String getExtension(String filename) {
        if (filename == null) return ".jpg";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase() : ".jpg";
    }
}
