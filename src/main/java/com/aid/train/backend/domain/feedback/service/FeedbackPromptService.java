package com.aid.train.backend.domain.feedback.service;

import com.aid.train.backend.domain.feedback.dto.request.FeedbackCreateRequest;
import com.aid.train.backend.domain.feedback.dto.response.FeedbackResponse;
import com.aid.train.backend.domain.session.entity.DialogueSession;
import com.aid.train.backend.domain.session.entity.Transcript;
import com.aid.train.backend.domain.session.enums.Speaker;
import com.aid.train.backend.global.exception.TrainException;
import com.aid.train.backend.global.exception.enums.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 전체 대화(세션) 기반의 AI 피드백 생성을 담당하는 서비스입니다.
 *
 * <p>핵심 기능</p>
 * <ul>
 *   <li><b>종합 프롬프트 생성</b>: 전체 대화 흐름과 사용자 발화를 정리하여 AI 모델에 전달할 입력을 구성함</li>
 *   <li><b>AI 호출 및 재시도</b>: 일시 오류 대비 최대 3회까지 지수형(1→2→3초) 대기 후 재시도함</li>
 *   <li><b>응답 파싱</b>: 점수/개선안/전체·문장별 분석 결과를 DTO/Response로 변환함</li>
 * </ul>
 *
 * <p>설계 원칙</p>
 * <ul>
 *   <li>도메인 호환성 유지(기존 생성 방식과의 병행 지원)</li>
 *   <li>로깅 일관성(세션 ID 중심 추적)</li>
 *   <li>예외 전파 시 도메인 표준 예외(TrainException)로 감싸서 의미 전달</li>
 * </ul>
 *
 * @author 왕택준
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackPromptService {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    // 재시도 설정
    private static final int MAX_RETRIES = 3;
    private static final int BASE_DELAY_MS = 1000; // 1초 기본 지연

    /**
     * 재시도 전 대기 시간(선형 백오프)을 적용합니다.
     *
     * @param attempt 현재 재시도 횟수(1부터 시작)
     */
    private void waitBeforeRetry(int attempt) {
        try {
            int delayMs = BASE_DELAY_MS * attempt; // 1초, 2초, 3초
            log.info("재시도 전 {}ms 대기 중...", delayMs);
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("재시도 대기 중 인터럽트 발생");
        }
    }

    /**
     * 종합 분석(전체 흐름 + 문장별 분석 + 개선안)이 포함된 응답을 생성합니다.
     *
     * <p>본 메서드는 UI에 즉시 제공 가능한 형태의 {@link FeedbackResponse}를 구성합니다.</p>
     *
     * @param dialogueSession 분석 대상 세션
     * @return 종합 분석이 포함된 피드백 응답
     * @throws TrainException AI 분석 실패 시(모든 재시도 실패 후) 발생
     */
    public FeedbackResponse generateFeedbackFromAI(DialogueSession dialogueSession) {
        log.info("종합 피드백 응답 생성 시작 - sessionId: {}", dialogueSession.getSessionId());

        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.info("종합 피드백 응답 생성 시도 {}/{} - sessionId: {}",
                        attempt, MAX_RETRIES, dialogueSession.getSessionId());

                // 1. 전체 대화 + 개별 문장 분석 프롬프트 생성
                String comprehensivePrompt = generatePrompt(dialogueSession);

                // 2. AI API 호출
                String aiResponse = callChatGPT(comprehensivePrompt);

                // 3. 종합 분석 결과 파싱
                FeedbackResponse response = parseComprehensiveResponse(aiResponse, dialogueSession);

                log.info("종합 피드백 응답 생성 성공 - sessionId: {}, attempt: {}",
                        dialogueSession.getSessionId(), attempt);

                return response;

            } catch (Exception e) {
                lastException = e;

                if (attempt < MAX_RETRIES) {
                    log.warn("종합 피드백 응답 생성 실패, 재시도 예정 - sessionId: {}, attempt: {}/{}, error: {}",
                            dialogueSession.getSessionId(), attempt, MAX_RETRIES, e.getMessage());

                    waitBeforeRetry(attempt);
                } else {
                    log.error("종합 피드백 응답 생성 최종 실패 - sessionId: {}, attempt: {}/{}, error: {}",
                            dialogueSession.getSessionId(), attempt, MAX_RETRIES, e.getMessage());
                }
            }
        }

        // 모든 재시도 실패
        log.error("종합 피드백 응답 생성 완전 실패 - sessionId: {}, 총 {}회 시도 완료",
                dialogueSession.getSessionId(), MAX_RETRIES);

        throw new TrainException(ErrorCode.AI_ANALYSIS_FAILED,
                String.format("종합 피드백 생성에 %d회 시도했지만 모두 실패했습니다. 마지막 오류: %s",
                        MAX_RETRIES, lastException.getMessage()));
    }

    /**
     * 전체 대화 흐름과 사용자 발화를 종합하여 AI 모델 입력 프롬프트를 생성합니다.
     *
     * <p>프롬프트 구성</p>
     * <ul>
     *   <li>전체 대화 로그(화자 레이블 포함)</li>
     *   <li>사용자 발화만 별도 목록화(문장 번호 부여)</li>
     *   <li>대표 발화(가장 긴 문장) 기반의 개선안 유도</li>
     * </ul>
     *
     * @param dialogueSession 분석 대상 세션
     * @return AI 모델 입력용 종합 프롬프트 문자열
     * @throws TrainException 대화 내용 부족 시 발생(INSUFFICIENT_DIALOGUE_CONTENT)
     */
    private String generatePrompt(DialogueSession dialogueSession) {
        List<Transcript> transcripts = dialogueSession.getTranscripts();

        log.info("종합 프롬프트 생성 - sessionId: {}, transcriptCount: {}",
                dialogueSession.getSessionId(), transcripts.size());

        if (transcripts.isEmpty()) {
            throw new TrainException(ErrorCode.INSUFFICIENT_DIALOGUE_CONTENT,
                    "분석할 대화 내용이 없습니다.");
        }

        // 분석 가능한 사용자 발화만 필터링
        List<Transcript> userTranscripts = transcripts.stream()
                .filter(Transcript::isAnalyzable)
                .sorted(Comparator.comparing(Transcript::getTimestamp))
                .toList();

        if (userTranscripts.isEmpty()) {
            throw new TrainException(ErrorCode.INSUFFICIENT_DIALOGUE_CONTENT,
                    "분석 가능한 사용자 발화가 충분하지 않습니다.");
        }

        // 전체 대화 흐름 구성 (AI와 USER 번갈아가며)
        StringBuilder fullConversation = new StringBuilder();
        fullConversation.append("=== 전체 대화 흐름 ===\n");
        for (Transcript transcript : transcripts) {
            String speaker = transcript.getSpeaker() == Speaker.USER ? "사용자" : "상대방";
            fullConversation.append(String.format("[%s] %s\n", speaker, transcript.getContent()));
        }

        // 사용자 발화만 번호를 매겨서 개별 분석용으로 구성
        StringBuilder userSentences = new StringBuilder();
        userSentences.append("\n=== 개별 분석 대상 (사용자 발화만) ===\n");
        for (int i = 0; i < userTranscripts.size(); i++) {
            userSentences.append(String.format("[문장 %d] %s\n",
                    i + 1, userTranscripts.get(i).getContent()));
        }

        // 대표 발화 선택 (가장 긴 문장)
        String representativeTranscript = userTranscripts.stream()
                .max(Comparator.comparingInt(t -> t.getContent().length()))
                .map(Transcript::getContent)
                .orElse(userTranscripts.get(0).getContent());

        return String.format("""
                        당신은 대화 훈련 전문가입니다. 다음 대화를 종합적으로 분석해주세요.
                        
                        ## 시나리오 정보
                        제목: %s
                        설명: %s
                        
                        %s
                        
                        %s
                        
                        ## 분석 요청사항
                        
                        **1단계: 전체 대화 흐름 분석**
                        - 대화가 어떻게 진행되었는지 전반적인 흐름 평가
                        - 사용자의 소통 패턴과 특징 분석
                        - 전체적인 개선 방향 제시
                        
                        **2단계: 개별 문장 분석**
                        - 각 사용자 발화의 구체적인 문제점 지적
                        - 문장별 개선된 버전 제시
                        - 문제의 심각도와 개선 방법 제안
                        
                        **3단계: 종합 점수 및 개선안**
                        - 전체 대화를 고려한 종합 점수 산출
                        - 3가지 스타일의 대표 개선안 제시 (대표 문장: "%s")
                        
                        다음 JSON 형식으로 응답해주세요:
                        
                        **중요: 점수 규칙**
                        - 각 항목은 0-25점 범위
                        - totalScore는 반드시 4개 항목의 합계와 정확히 일치해야 함
                        - totalScore = speechRateScore + fillerWordsScore + politenessScore + clarityScore
                        
                        ```json
                        {
                          "totalScore": 전체점수(0-100, 반드시 아래 4개 점수의 합계),
                          "speechRateScore": 발화속도점수(0-25),
                          "fillerWordsScore": 추임새점수(0-25),
                          "politenessScore": 공손도점수(0-25),
                          "clarityScore": 명료성점수(0-25),
                          "improvementPoints": [
                            {
                              "type": "문제유형",
                              "description": "문제설명",
                              "suggestion": "개선제안"
                            }
                          ],
                          "originalTranscript": "%s",
                          "alternativeA": "간결한 스타일 개선안",
                          "alternativeB": "공손한 스타일 개선안",
                          "alternativeC": "따뜻한 스타일 개선안",
                          "overallAnalysis": {
                            "conversationFlow": "전체 대화 흐름에 대한 평가 (어떻게 진행되었는지, 효율성은 어떤지)",
                            "communicationPattern": "사용자의 소통 패턴 분석 (자신감, 예의, 명확성 등의 전반적 특징)",
                            "overallImprovements": [
                              {
                                "category": "conversation_flow|confidence|structure",
                                "description": "개선영역설명",
                                "suggestion": "구체적개선방법"
                              }
                            ]
                          },
                          "sentenceAnalyses": [
                            {
                              "sequence": 문장순서,
                              "content": "원본문장",
                              "issues": [
                                {
                                  "type": "filler_words|incomplete_sentence|vague_explanation",
                                  "count": 문제발생횟수,
                                  "impact": "high|medium|low",
                                  "suggestion": "개선제안"
                                }
                              ],
                              "improvedVersion": "개선된문장"
                            }
                          ],
                          "conversationImprovement": {
                            "currentPattern": "현재 대화에서 나타난 패턴",
                            "improvedPattern": "이상적인 대화 패턴",
                            "fullImprovedDialogue": "전체 대화를 개선한 완전한 예시"
                          }
                        }
                        ```
                        
                        JSON만 응답하고 다른 설명은 포함하지 마세요.
                        """,
                dialogueSession.getScenario().getTitle(),
                dialogueSession.getScenario().getDescription(),
                fullConversation,
                userSentences,
                representativeTranscript,
                representativeTranscript
        );
    }

    /**
     * 종합 분석 응답을 파싱하여 {@link FeedbackResponse}로 변환합니다.
     *
     * @param aiResponse      AI 원본 응답 문자열
     * @param dialogueSession 파싱 컨텍스트가 되는 세션
     * @return 종합 분석이 포함된 {@link FeedbackResponse}
     * @throws TrainException JSON 블록 추출 실패 또는 필드 파싱 실패 시 발생
     */
    private FeedbackResponse parseComprehensiveResponse(String aiResponse, DialogueSession dialogueSession) {
        log.info("종합 분석 응답 파싱 시작 - sessionId: {}", dialogueSession.getSessionId());

        try {
            // JSON 블록 추출
            String jsonContent = extractJsonFromResponse(aiResponse);
            JsonNode jsonNode = objectMapper.readTree(jsonContent);

            // 기본 피드백 정보 추출
            int totalScore = jsonNode.get("totalScore").asInt();
            int speechRateScore = jsonNode.get("speechRateScore").asInt();
            int fillerWordsScore = jsonNode.get("fillerWordsScore").asInt();
            int politenessScore = jsonNode.get("politenessScore").asInt();
            int clarityScore = jsonNode.get("clarityScore").asInt();

            // 점수 검증 및 자동 보정
            int calculatedTotal = speechRateScore + fillerWordsScore + politenessScore + clarityScore;
            if (totalScore != calculatedTotal) {
                log.warn("AI 점수 불일치 감지 - sessionId: {}, totalScore: {}, calculated: {} (자동 보정)",
                        dialogueSession.getSessionId(), totalScore, calculatedTotal);
                
                // totalScore를 계산된 합계로 보정
                totalScore = calculatedTotal;
                
                // 100점을 초과하면 비율로 조정
                if (totalScore > 100) {
                    double ratio = 100.0 / totalScore;
                    speechRateScore = (int) Math.round(speechRateScore * ratio);
                    fillerWordsScore = (int) Math.round(fillerWordsScore * ratio);
                    politenessScore = (int) Math.round(politenessScore * ratio);
                    clarityScore = (int) Math.round(clarityScore * ratio);
                    totalScore = speechRateScore + fillerWordsScore + politenessScore + clarityScore;
                    
                    log.info("점수 비율 조정 완료 - sessionId: {}, 조정된 totalScore: {}",
                            dialogueSession.getSessionId(), totalScore);
                }
            }

            // 전체 대화 분석 파싱
            FeedbackResponse.OverallAnalysis overallAnalysis = parseOverallAnalysis(jsonNode.get("overallAnalysis"));

            // 문장별 분석 파싱
            List<FeedbackResponse.SentenceAnalysis> sentenceAnalyses = parseSentenceAnalyses(jsonNode.get("sentenceAnalyses"));

            // 대화 개선안 파싱
            FeedbackResponse.ConversationImprovement conversationImprovement = parseConversationImprovement(jsonNode.get("conversationImprovement"));

            // 점수 등급 계산
            String scoreGrade = calculateGrade(totalScore);

            // 종합 분석이 포함된 완전한 응답 생성
            return FeedbackResponse.builder()
                    .id(null) // 아직 저장되지 않음
                    .sessionId(dialogueSession.getSessionId())
                    .scenarioId(dialogueSession.getScenario().getId())
                    .scenarioTitle(dialogueSession.getScenario().getTitle())
                    .totalScore(totalScore)
                    .scoreGrade(scoreGrade)
                    .speechRateScore(speechRateScore)
                    .fillerWordsScore(fillerWordsScore)
                    .politenessScore(politenessScore)
                    .clarityScore(clarityScore)
                    .improvementPoints(jsonNode.get("improvementPoints").toString())
                    .originalTranscript(jsonNode.get("originalTranscript").asText())
                    .alternativeA(jsonNode.get("alternativeA").asText())
                    .alternativeB(jsonNode.get("alternativeB").asText())
                    .alternativeC(jsonNode.get("alternativeC").asText())
                    .chosenAlternative(null)
                    .finalChoice(null)
                    .isChoiceComplete(false)
                    .createdAt(null) // 저장 시점에 설정
                    .updatedAt(null)
                    // 새로운 종합 분석 포함
                    .overallAnalysis(overallAnalysis)
                    .sentenceAnalyses(sentenceAnalyses)
                    .conversationImprovement(conversationImprovement)
                    .build();

        } catch (Exception e) {
            log.error("종합 분석 응답 파싱 실패 - sessionId: {}", dialogueSession.getSessionId(), e);
            throw new TrainException(ErrorCode.AI_RESPONSE_PARSE_ERROR,
                    "종합 분석 결과 파싱에 실패했습니다: " + e.getMessage());
        }
    }

    /**
     * 전체 대화 분석 노드를 파싱하여 {@link FeedbackResponse.OverallAnalysis}로 변환합니다.
     *
     * @param overallNode 전체 대화 분석 JSON 노드
     * @return 파싱된 전체 대화 분석(파싱 실패 또는 누락 시 {@code null})
     */
    private FeedbackResponse.OverallAnalysis parseOverallAnalysis(JsonNode overallNode) {
        if (overallNode == null || overallNode.isNull()) return null;

        try {
            List<FeedbackResponse.OverallImprovement> improvements = new ArrayList<>();

            if (overallNode.has("overallImprovements")) {
                JsonNode improvementsNode = overallNode.get("overallImprovements");
                for (JsonNode improvement : improvementsNode) {
                    improvements.add(FeedbackResponse.OverallImprovement.builder()
                            .category(improvement.get("category").asText())
                            .description(improvement.get("description").asText())
                            .suggestion(improvement.get("suggestion").asText())
                            .build());
                }
            }

            return FeedbackResponse.OverallAnalysis.builder()
                    .conversationFlow(overallNode.get("conversationFlow").asText())
                    .communicationPattern(overallNode.get("communicationPattern").asText())
                    .overallImprovements(improvements)
                    .build();
        } catch (Exception e) {
            log.warn("전체 분석 파싱 실패", e);
            return null;
        }
    }

    /**
     * 문장별 분석 목록을 파싱하여 {@code List<SentenceAnalysis>}로 변환합니다.
     *
     * @param sentencesNode 문장별 분석 JSON 배열 노드
     * @return 파싱된 문장별 분석 목록(파싱 실패 시 수집된 항목만 반환)
     */
    private List<FeedbackResponse.SentenceAnalysis> parseSentenceAnalyses(JsonNode sentencesNode) {
        List<FeedbackResponse.SentenceAnalysis> analyses = new ArrayList<>();

        if (sentencesNode == null || sentencesNode.isNull()) return analyses;

        try {
            for (JsonNode sentence : sentencesNode) {
                List<FeedbackResponse.SentenceIssue> issues = new ArrayList<>();

                if (sentence.has("issues")) {
                    for (JsonNode issue : sentence.get("issues")) {
                        issues.add(FeedbackResponse.SentenceIssue.builder()
                                .type(issue.get("type").asText())
                                .count(issue.has("count") ? issue.get("count").asInt() : null)
                                .impact(issue.get("impact").asText())
                                .suggestion(issue.get("suggestion").asText())
                                .build());
                    }
                }

                analyses.add(FeedbackResponse.SentenceAnalysis.builder()
                        .sequence(sentence.get("sequence").asInt())
                        .content(sentence.get("content").asText())
                        .issues(issues)
                        .improvedVersion(sentence.get("improvedVersion").asText())
                        .build());
            }
        } catch (Exception e) {
            log.warn("문장별 분석 파싱 실패", e);
        }

        return analyses;
    }

    /**
     * 대화 전체 개선안 노드를 파싱하여 {@link FeedbackResponse.ConversationImprovement}로 변환합니다.
     *
     * @param improvementNode 대화 개선안 JSON 노드
     * @return 파싱된 대화 개선안(파싱 실패 또는 누락 시 {@code null})
     */
    private FeedbackResponse.ConversationImprovement parseConversationImprovement(JsonNode improvementNode) {
        if (improvementNode == null || improvementNode.isNull()) return null;

        try {
            return FeedbackResponse.ConversationImprovement.builder()
                    .currentPattern(improvementNode.get("currentPattern").asText())
                    .improvedPattern(improvementNode.get("improvedPattern").asText())
                    .fullImprovedDialogue(improvementNode.get("fullImprovedDialogue").asText())
                    .build();
        } catch (Exception e) {
            log.warn("대화 개선안 파싱 실패", e);
            return null;
        }
    }

    /**
     * 총점 구간에 따른 등급을 계산합니다.
     *
     * @param score 총점(0~100)
     * @return 등급 문자열(A/B/C/D/F)
     */
    private String calculateGrade(int score) {
        if (score >= 90) return "A";
        if (score >= 80) return "B";
        if (score >= 70) return "C";
        if (score >= 60) return "D";
        return "F";
    }

    /**
     * AI 모델(ChatGPT)에 프롬프트를 전달하여 응답을 수신합니다.
     *
     * @param prompt 모델 입력 프롬프트
     * @return 모델 원문 응답 콘텐츠
     * @throws Exception 모델 호출 실패 시 발생
     */
    private String callChatGPT(String prompt) throws Exception {
        log.info("ChatGPT 4.0 호출 시작 - prompt length: {}", prompt.length());

        try {
            Prompt chatPrompt = new Prompt(prompt);
            ChatResponse response = chatModel.call(chatPrompt);

            String aiResponse = response.getResult().getOutput().getContent();
            log.info("ChatGPT 4.0 응답 받음 - response length: {}", aiResponse.length());

            return aiResponse;

        } catch (Exception e) {
            log.error("ChatGPT 4.0 호출 실패", e);
            throw new Exception("AI API 호출에 실패했습니다: " + e.getMessage());
        }
    }

    /**
     * AI 응답(JSON)을 파싱하여 저장용 {@link FeedbackCreateRequest}로 변환합니다.
     *
     * @param aiResponse    AI 원본 응답
     * @param sessionId     세션 ID(트래킹 용도)
     * @param aiPrompt      사용된 프롬프트(디버깅/감사 로그용)
     * @param aiRawResponse 원본 응답(디버깅/감사 로그용)
     * @return 저장용 피드백 생성 요청 DTO
     * @throws TrainException JSON 블록 추출 실패 또는 필드 파싱 실패 시 발생
     */
    private FeedbackCreateRequest parseAIResponse(String aiResponse, String sessionId,
                                                  String aiPrompt, String aiRawResponse) {
        log.info("AI 응답 파싱 시작 - sessionId: {}", sessionId);

        try {
            // JSON 블록 추출
            String jsonContent = extractJsonFromResponse(aiResponse);
            JsonNode jsonNode = objectMapper.readTree(jsonContent);

            // 점수 검증 및 보정
            int totalScore = jsonNode.get("totalScore").asInt();
            int speechRateScore = jsonNode.get("speechRateScore").asInt();
            int fillerWordsScore = jsonNode.get("fillerWordsScore").asInt();
            int politenessScore = jsonNode.get("politenessScore").asInt();
            int clarityScore = jsonNode.get("clarityScore").asInt();

            // FeedbackCreateRequest 생성 (기존 호환성)
            return FeedbackCreateRequest.builder()
                    .sessionId(sessionId)
                    .totalScore(totalScore)
                    .speechRateScore(speechRateScore)
                    .fillerWordsScore(fillerWordsScore)
                    .politenessScore(politenessScore)
                    .clarityScore(clarityScore)
                    .improvementPoints(jsonNode.get("improvementPoints").toString())
                    .originalTranscript(jsonNode.get("originalTranscript").asText())
                    .alternativeA(jsonNode.get("alternativeA").asText())
                    .alternativeB(jsonNode.get("alternativeB").asText())
                    .alternativeC(jsonNode.get("alternativeC").asText())
                    .aiPrompt(aiPrompt)
                    .aiRawResponse(aiRawResponse)
                    .build();

        } catch (Exception e) {
            log.error("AI 응답 JSON 파싱 실패 - sessionId: {}", sessionId, e);
            throw new TrainException(ErrorCode.AI_RESPONSE_PARSE_ERROR,
                    "AI 응답 파싱에 실패했습니다: " + e.getMessage());
        }
    }

    /**
     * 모델 응답 문자열에서 JSON 본문을 추출합니다.
     *
     * <p>우선순위</p>
     * <ol>
     *   <li>코드펜스 블록(```json ... ```)</li>
     *   <li>최초 '{'부터 최후 '}'까지의 서브스트링</li>
     * </ol>
     *
     * @param response 모델 원문 응답
     * @return 추출된 JSON 문자열
     * @throws TrainException JSON 경계를 찾을 수 없는 경우 발생
     */
    private String extractJsonFromResponse(String response) {
        // ```json 블록에서 JSON 추출
        int jsonStart = response.indexOf("```json");
        int jsonEnd = response.indexOf("```", jsonStart + 7);

        if (jsonStart != -1 && jsonEnd != -1) {
            return response.substring(jsonStart + 7, jsonEnd).trim();
        }

        // JSON 블록이 없으면 전체 응답에서 { } 블록 찾기
        int braceStart = response.indexOf("{");
        int braceEnd = response.lastIndexOf("}");

        if (braceStart != -1 && braceEnd != -1) {
            return response.substring(braceStart, braceEnd + 1);
        }

        throw new TrainException(ErrorCode.AI_RESPONSE_PARSE_ERROR,
                "AI 응답에서 JSON을 찾을 수 없습니다.");
    }
}
