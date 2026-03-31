package com.effectivedisco.controller;

import com.effectivedisco.domain.NotificationType;
import com.effectivedisco.service.NotificationSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 알림 수신 설정 REST API 컨트롤러.
 *
 * GET  /api/notification-settings          → 현재 사용자의 알림 설정 조회
 * PUT  /api/notification-settings/{type}   → 특정 알림 타입 수신 on/off 변경
 */
@Tag(name = "Notification Settings", description = "알림 수신 설정 API")
@RestController
@RequestMapping("/api/notification-settings")
@RequiredArgsConstructor
public class NotificationSettingController {

    private final NotificationSettingService notificationSettingService;

    @Operation(summary = "알림 수신 설정 조회", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    public ResponseEntity<Map<NotificationType, Boolean>> getSettings(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(notificationSettingService.getSettings(userDetails.getUsername()));
    }

    @Operation(summary = "알림 수신 설정 변경", security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/{type}")
    public ResponseEntity<Void> updateSetting(
            @PathVariable NotificationType type,
            @RequestParam boolean enabled,
            @AuthenticationPrincipal UserDetails userDetails) {
        notificationSettingService.updateSetting(userDetails.getUsername(), type, enabled);
        return ResponseEntity.ok().build();
    }
}
