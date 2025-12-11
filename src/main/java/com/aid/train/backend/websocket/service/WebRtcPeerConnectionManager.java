package com.aid.train.backend.websocket.service;

import com.aid.train.backend.websocket.dto.server.GptSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebRtcPeerConnectionManager {


    @Value("${spring.ai.openai.api-key}")
    private String openAiApiKey;

    // sessionId → GptSession 관리
    private final Map<String, GptSession> gptSessions = new ConcurrentHashMap<>();

    // Phase 1: GPT Realtime HTTP SDP 교환
    public String connectToGptRealtime(String sessionId, String clientOfferSdp) {
        try {
            log.info("GPT Realtime 연결 시작 - sessionId: {}", sessionId);

            String gptUrl = "https://api.openai.com/v1/realtime?model=gpt-realtime";
            HttpClient httpClient = HttpClient.newHttpClient();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(gptUrl))
                    .header("Authorization", "Bearer " + openAiApiKey)
                    .header("Content-type", "application/sdp")
                    .POST(HttpRequest.BodyPublishers.ofString(clientOfferSdp))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                log.error("GPT SDP 수신 실패: {} - {}", response.statusCode(), response.body());
                throw new RuntimeException("GPT SDP 교환 실패");
            }

            String gptAnswerSdp = response.body();
            log.info("GPT Answer SDP 수신 성공 - 길이: {}", gptAnswerSdp.length());

            // 세션 생성
            GptSession gptSession = GptSession.builder()
                    .sessionId(sessionId)
                    .build();
            gptSessions.put(sessionId, gptSession);

            return gptAnswerSdp;

        } catch (Exception e) {
            log.error("GPT Realtime 연결 실패 - sessionId: {}, error: {}", sessionId, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // Phase 2: 원격 ICE Candidate 추가
    public void addRemoteIceCandidate(String sessionId, Map<String, Object> candidate) {
        GptSession gptSession = gptSessions.get(sessionId);
        if (gptSession != null) {
            gptSession.addIceCandidate(candidate);
            log.info("원격 ICE Candidate 추가 - sessionId: {}", sessionId);
        } else {
            log.warn("세션 없음 - ICE Candidate 무시 - sessionId: {}", sessionId);
        }
    }

    // 세션 종료 시 정리
    public void removeSession(String sessionId) {
        gptSessions.remove(sessionId);
        log.info("GPT 세션 제거 - sessionId: {}", sessionId);
    }
}
