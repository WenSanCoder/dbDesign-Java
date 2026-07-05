package com.zjut.edusystem.profile;

import com.zjut.edusystem.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

@Service
public class AvatarStorageService {
    @Value("${edu-system.upload.image-dir:uploads/image}")
    private String localImageDir;

    @Value("${edu-system.upload.remote.enabled:false}")
    private boolean remoteEnabled;

    @Value("${edu-system.upload.remote.upload-url:}")
    private String remoteUploadUrl;

    @Value("${edu-system.upload.remote.username:}")
    private String remoteUsername;

    @Value("${edu-system.upload.remote.password:}")
    private String remotePassword;

    private final RestTemplate restTemplate = new RestTemplate();

    public void saveAvatar(String filename, MultipartFile file) {
        if (remoteEnabled) {
            saveRemote(filename, file);
            return;
        }
        saveLocal(filename, file);
    }

    private void saveLocal(String filename, MultipartFile file) {
        Path targetDir = Paths.get(localImageDir).toAbsolutePath().normalize();
        Path targetFile = targetDir.resolve(filename).normalize();
        if (!targetFile.startsWith(targetDir)) {
            throw new BusinessException("非法文件路径");
        }

        try {
            Files.createDirectories(targetDir);
            file.transferTo(targetFile);
        } catch (IOException ex) {
            throw new BusinessException("头像保存失败：" + ex.getMessage());
        }
    }

    private void saveRemote(String filename, MultipartFile file) {
        validateRemoteConfig();

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(file.getContentType() == null ? "application/octet-stream" : file.getContentType()));
            if (StringUtils.hasText(remoteUsername) || StringUtils.hasText(remotePassword)) {
                String token = (remoteUsername == null ? "" : remoteUsername) + ":" + (remotePassword == null ? "" : remotePassword);
                headers.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)));
            }

            HttpEntity<byte[]> request = new HttpEntity<>(file.getBytes(), headers);
            ResponseEntity<Void> response = restTemplate.exchange(buildUploadUrl(filename), HttpMethod.PUT, request, Void.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new BusinessException("远端图片服务返回异常状态：" + response.getStatusCode());
            }
        } catch (IOException ex) {
            throw new BusinessException("读取头像文件失败：" + ex.getMessage());
        } catch (RestClientException ex) {
            throw new BusinessException("头像上传到远端 HTTP 服务失败：" + ex.getMessage());
        }
    }

    private void validateRemoteConfig() {
        if (!StringUtils.hasText(remoteUploadUrl)) {
            throw new BusinessException("远端上传已开启，但 REMOTE_UPLOAD_URL 未配置");
        }
        if (!remoteUploadUrl.startsWith("http://") && !remoteUploadUrl.startsWith("https://")) {
            throw new BusinessException("REMOTE_UPLOAD_URL 必须以 http:// 或 https:// 开头");
        }
    }

    private String buildUploadUrl(String filename) {
        String normalized = remoteUploadUrl.endsWith("/") ? remoteUploadUrl : remoteUploadUrl + "/";
        return normalized + filename;
    }
}
