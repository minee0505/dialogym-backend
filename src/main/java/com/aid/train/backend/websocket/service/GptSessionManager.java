package com.aid.train.backend.websocket.service;

import com.aid.train.backend.websocket.dto.client.SessionInitMessage;
import com.aid.train.backend.websocket.dto.server.GptSession;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GPT Realtime API WebSocket 연결을 관리하는 매니저
 *
 * 역할:
 * - sessionId별 GPT WebSocket 연결 생성 및 관리
 * - GPT에 음성 데이터 전송
 * - GPT로부터 AI 응답 수신
 * - GPT 세션 생명주기 관리
 *
 * 왜 필요한가?
 * - 각 사용자마다 독립적인 GPT WebSocket 연결이 필요
 * - 사용자 A의 음성 → GPT A 세션
 * - 사용자 B의 음성 → GPT B 세션
 * - 섞이면 안 되므로 분리 관리
 *
 * GPT Realtime API란?
 * - OpenAI의 실시간 음성 대화 API
 * - WebSocket으로 음성 데이터를 주고받음
 * - STT + LLM + TTS를 하나로 통합 처리
 *
 * @author 김경민
 * @since 2025-10-15
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GptSessionManager {

    /**
     * OpenAI API Key (환경변수에서 주입)
     */
    @Value("${spring.ai.openai.api-key}")
    private String openAiApiKey;

    private final ObjectMapper objectMapper;
    private final WebRtcStateManager webRtcStateManager;

    /**
     * sessionId → GPT WebSocket 연결 매핑
     * 각 사용자별로 독립적인 GPT 연결 유지
     */
    private final Map<String, GptSession> gptSessionMap = new ConcurrentHashMap<>();

    /**
     * sessionId → GPT 응답 핸들러 매핑
     * GPT로부터 받은 음성을 처리할 핸들러
     */
    private final Map<String, GptResponseHandler> responseHandlers = new ConcurrentHashMap<>();

    /**
     * GPT 세션을 생성하고 연결합니다.
     *
     * 언제 호출되나?
     * - SessionCoordinator의 initializeSession()
     * - 사용자가 WebSocket 연결 성공 후
     *
     * 무엇을 하나?
     * 1. GPT WebSocket 연결 생성
     * 2. 인증 헤더 설정 (API Key)
     * 3. 세션 초기화 메시지 전송
     * 4. 시나리오 프롬프트 전송
     *
     * @param sessionId 대화 세션 ID
     * @param responseHandler GPT 응답을 처리할 핸들러
     */
    /*public CompletableFuture<WebSocketSession> createGptSession(String sessionId, SessionInitMessage initMessage, GptResponseHandler responseHandler) {

        try {
            log.info("GPT 세션 생성 시작 - sessionId: {},", sessionId);

            // 1. WebSocket 컨테이너 생성 및 버퍼 크기 설정
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            
            // 버퍼 크기를 2MB로 증가 (GPT 오디오 델타 처리용)
            container.setDefaultMaxTextMessageBufferSize(2 * 1024 * 1024);  // 2MB
            container.setDefaultMaxBinaryMessageBufferSize(2 * 1024 * 1024); // 2MB
            
            log.info("WebSocket 버퍼 크기 설정 완료 - 2MB");

            // 2. StandardWebSocketClient 생성
            StandardWebSocketClient standardClient = new StandardWebSocketClient(container);

            // 3. GPT 응답 핸들러 등록
            responseHandlers.put(sessionId, responseHandler);

            // 4. Authorization 헤더 추가
            org.springframework.web.socket.WebSocketHttpHeaders headers =
                    new org.springframework.web.socket.WebSocketHttpHeaders();
            headers.add("Authorization", "Bearer " + openAiApiKey);
            headers.add("OpenAI-Beta", "realtime=v1");

            log.debug("API Key 사용");

            URI uri = new URI("wss://api.openai.com/v1/realtime?model=gpt-realtime");

            CompletableFuture<WebSocketSession> future = standardClient.execute(

                    new TextWebSocketHandler() {
                        @Override
                        public void afterConnectionEstablished(WebSocketSession session) {
                            log.info("GPT WebSocket 연결 성공 - sessionId: {}", sessionId);
                            GptSession gptSession = GptSession.builder()
                                    .sessionId(sessionId)
                                    .webSocketSession(session)
                                    .isReady(new AtomicBoolean(true))
                                    .build();
                            gptSessionMap.put(sessionId, gptSession);

                        }

                        @Override
                        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                            String payload = message.getPayload();
                            log.debug("GPT 응답 수신 - sessionId: {}, payload: {}", sessionId, payload);

                            // 여기서 프롬프트 전송
                            try {
                                Map<String, Object> json = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
                                String type = (String) json.get("type");

                                // 세션 생성 완료 이벤트 감지 -> 프롬프트 전송
                                if ("session.created".equals(type)) {
                                    log.info("GPT 세션 초기화 완료 - 프롬프트 전송 시작");

                                    String prompt = objectMapper.writeValueAsString(initMessage);
                                    synchronized(session) {
                                        session.sendMessage(new TextMessage(prompt));
                                    }

                                    log.info("GPT 시나리오 전송 완료 - sessionId: {}", sessionId);
                                    return;
                                }

                                GptResponseHandler handler = responseHandlers.get(sessionId);
                                if (handler != null) {
                                    handler.handleGptResponse(sessionId, message.getPayload());
                                }

                            } catch (Exception e) {
                                log.error("GPT 메시지 처리 중 오류 - sessionId: {}", sessionId, e);
                            }
                        }

                        @Override
                        public void handleTransportError(WebSocketSession session, Throwable exception) {
                            log.error("GPT WebSocket 에러 - sessionId: {}", sessionId, exception);
                        }

                        @Override
                        public void afterConnectionClosed(WebSocketSession session, CloseStatus status)  {
                            log.error("GPT WebSocket 연결 종료 감지 - sessionId: {}, Status: {} ({})",
                                    sessionId, status.getCode(), status.getReason());

                            // 세션 정리
                            gptSessionMap.remove(sessionId);
                            responseHandlers.remove(sessionId);
                            
                            // 1009 에러는 로그만 남기고 재연결은 하지 않음
                            // (버퍼 크기를 2MB로 증가시켰으므로 재발하지 않아야 함)
                            if (status.getCode() == 1009) {
                                log.error("메시지 크기 초과 에러 발생 - 버퍼 크기 확인 필요 - sessionId: {}", sessionId);
                            }
                        }
                    },
                    headers, // WebSocketHttpHeaders
                    uri      // 연결할 URI
            );

            // 연결 성공/실패 로그
            future.whenComplete((session, ex) -> {
                if (ex != null) {
                    log.error("GPT WebSocket handshake 실패 - sessionId: {}", sessionId, ex);
                } else {
                    log.info("GPT WebSocket handshake 성공 - sessionId: {}", sessionId);
                }
            });

            return future;

        } catch (Exception e) {
            log.error("GPT 세션 생성 실패 - sessionId: {}", sessionId, e);
            throw new RuntimeException("GPT 세션 생성 실패: " + e.getMessage(), e);
        }

    }*/

    /**
     * GPT에 음성 데이터를 전송합니다.
     *
     * 언제 호출되나?
     * - SessionCoordinator의 routeAudio()
     * - 사용자 음성을 받았을 때
     *
     * 메시지 형식:
     * {
     *   "type": "input_audio_buffer.append",
     *   "audio": "base64로 인코딩된 PCM 16kHz 음성 데이터"
     * }
     *
     * @param sessionId 대화 세션 ID
     * @param audioData PCM 16kHz 음성 데이터 (바이트 배열)
     */
   /* public void sendAudioToGpt(String sessionId, byte[] audioData) {
        try {
            // 1. 음성 데이터를 Base64로 인코딩
            String base64Audio = Base64.getEncoder().encodeToString(audioData) ;

            // 2. GPT 메시지 생성
            String message = String.format("""
                {
                    "type": "input_audio_buffer.append",
                    "audio": "%s"
                }
                """, base64Audio);

            // 3. GPT에 전송
            sendToGpt(sessionId, message);

            log.debug("GPT에 음성 전송 - sessionId: {}, 크기: {} bytes",
                    sessionId, audioData.length);

        } catch (Exception e) {
            log.error("GPT 음성 전송 실패 - sessionId: {}", sessionId, e);
        }
    }

    *//**
     * GPT WebSocket으로 메시지를 전송합니다.
     *
     * @param sessionId 대화 세션 ID
     * @param message 전송할 JSON 메시지
     *//*
    public void sendToGpt(String sessionId, String message) {
        GptSession session = gptSessionMap.get(sessionId);
        WebSocketSession gptWsSession = session.getWebSocketSession();

        if (session == null) {
            log.error("GPT 세션을 찾을 수 없음 - sessionId: {}", sessionId);
            log.error("현재 등록된 세션들: {}", gptSessionMap.keySet());
            return;
        }

        if (gptWsSession == null || !gptWsSession.isOpen()) {
            log.error("GPT WebSocket이 없거나 닫혀있음 - sessionId: {}", sessionId);
            return;
        }

        try {
            synchronized(gptWsSession) {
                gptWsSession.sendMessage(new TextMessage(message));
            }
        } catch (Exception e) {
            log.error("GPT 메시지 전송 실패 - sessionId: {}", sessionId, e);
        }
    }*/

    /**
     * GPT 세션을 조회합니다.
     *
     * @param sessionId 대화 세션 ID
     * @return GPT WebSocket 연결
     */
    public GptSession getGptSession(String sessionId) {
        return gptSessionMap.get(sessionId);
    }

    /**
     * GPT 세션이 존재하는지 확인합니다.
     *
     * @param sessionId 대화 세션 ID
     * @return 존재 여부
     */
    public boolean hasGptSession(String sessionId) {
        GptSession gptSession = gptSessionMap.get(sessionId);
        if (gptSession == null) return false;
        WebSocketSession session = gptSession.getWebSocketSession();
        return session != null && session.isOpen();
    }

    /**
     * Gpt 세션을 등록합니다.
     *
     * @param sessionId 대화 세션 Id
     */
    public void registerGptSession(String sessionId, GptSession gptSession) {
        gptSessionMap.put(sessionId, gptSession);
    }

    /**
     * GPT 세션을 종료합니다.
     *
     * 언제 호출되나?
     * - SessionCoordinator의 terminateSession()
     * - 사용자가 대화를 종료했을 때
     *
     * @param sessionId 대화 세션 ID
     */
    public void closeGptSession(String sessionId) {
        try {
            // 1. GPT WebSocket 연결 종료
            WebSocketSession gptSession = gptSessionMap.remove(sessionId).getWebSocketSession();
            if (gptSession != null && gptSession.isOpen()) {
                gptSession.close();
                log.info("GPT 세션 종료 완료 - sessionId: {}", sessionId);
            }

            // 2. 응답 핸들러 제거
            responseHandlers.remove(sessionId);

        } catch (Exception e) {
            log.error("GPT 세션 종료 실패 - sessionId: {}", sessionId, e);
        }
    }

    /**
     * 현재 활성화된 GPT 세션 수를 반환합니다.
     *
     * @return GPT 세션 수
     */
    public int getActiveGptSessionCount() {
        return gptSessionMap.size();
    }

    /**
     * 모든 GPT 세션을 종료합니다.
     *
     * 언제 사용하나?
     * - 서버 종료 시
     */
    public void closeAll() {
        int count = gptSessionMap.size();
        gptSessionMap.forEach((sessionId, gptSession) -> {
            WebSocketSession session = gptSession.getWebSocketSession();
            try {
                if (session.isOpen()) {
                    session.close();
                }
            } catch (Exception e) {
                log.error("GPT 세션 종료 실패 - sessionId: {}", sessionId, e);
            }
        });
        gptSessionMap.clear();
        responseHandlers.clear();
        log.info("모든 GPT 세션 종료 완료 - 종료된 세션 수: {}", count);
    }

    /**
     * GPT 응답 처리 핸들러 인터페이스
     *
     * GPT로부터 받은 응답을 처리하는 콜백
     * SessionCoordinator에서 구현하여 사용
     */
    @FunctionalInterface
    public interface GptResponseHandler {
        void handleGptResponse(String sessionId, String jsonResponse);
    }


    /*public String connectToGptRealtime(String sessionId, String clientOfferSdp) {
        try {
            webRtcStateManager.updateState(sessionId, WebRtcStateManager.State.CONNECTING);
            log.info("connectToGptRealtime() 호출됨 - sessionId: {}", sessionId);
            String gptUrl = "https://api.openai.com/v1/realtime?model=gpt-realtime";
            HttpClient httpClient = HttpClient.newHttpClient();

            // offer sdp -> gpt 전송
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(gptUrl))
                    .header("Authorization", "Bearer " + openAiApiKey)
                    .header("Content-type", "application/sdp")
                    .POST(HttpRequest.BodyPublishers.ofString(clientOfferSdp))
                    .build();

            log.info("gpt에게 sdp 전송: {}", clientOfferSdp);

            // gpt sdp -> 수신
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if(response.statusCode() / 100 != 2) {
                log.error("gpt sdp 수신 에러 발생: {} - {}", response.statusCode(), response.body());
                throw new RuntimeException("gpt webRtc 연결 실패");
            }

            String gptAnswerSdp = response.body();
            log.info("gpt Answer sdp 수신 성공: {}", gptAnswerSdp);

            webRtcStateManager.updateState(sessionId, WebRtcStateManager.State.CONNECTED);
            return gptAnswerSdp;

        } catch (Exception e) {
            log.error("gpt 연결 실패: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }*/
}