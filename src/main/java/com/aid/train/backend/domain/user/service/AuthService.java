package com.aid.train.backend.domain.user.service;

import com.aid.train.backend.domain.terms.service.TermsService;
import com.aid.train.backend.domain.user.dto.request.LoginRequestDto;
import com.aid.train.backend.domain.user.dto.request.SocialSignupCompleteRequestDto;
import com.aid.train.backend.domain.user.dto.response.LoginResponseDto;
import com.aid.train.backend.domain.user.dto.response.TokenRefreshResponseDto;
import com.aid.train.backend.domain.user.entity.RefreshToken;
import com.aid.train.backend.domain.user.entity.SocialAccount;
import com.aid.train.backend.domain.user.entity.User;
import com.aid.train.backend.domain.user.enums.Provider;
import com.aid.train.backend.domain.user.enums.UserStatus;
import com.aid.train.backend.domain.user.repository.RefreshTokenRepository;
import com.aid.train.backend.domain.user.repository.SocialAccountRepository;
import com.aid.train.backend.domain.user.repository.UserRepository;
import com.aid.train.backend.domain.verification.dto.response.SocialCallbackResponseDto;
import com.aid.train.backend.domain.verification.entity.OneTimeCode;
import com.aid.train.backend.domain.verification.entity.PendingSocialUser;
import com.aid.train.backend.domain.verification.repository.OneTimeCodeRepository;
import com.aid.train.backend.domain.verification.repository.PendingSocialUserRepository;
import com.aid.train.backend.global.exception.TrainException;
import com.aid.train.backend.global.exception.enums.ErrorCode;
import com.aid.train.backend.global.security.jwt.JwtTokenProvider;
import com.aid.train.backend.global.util.LogMaskingUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 인증(Authentication) 관련 비즈니스 로직을 처리하는 서비스 클래스입니다.
 * 로컬 로그인, 토큰 발급/갱신, 로그아웃, 일회용 코드 교환 기능을 제공합니다.
 * <p>
 * **DB 기반 일회용 코드 관리:**
 * Redis 대신 OneTimeCode 테이블을 사용하여 일회용 코드를 관리합니다.
 *
 * @author 왕택준
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final PendingSocialUserRepository pendingSocialUserRepository;
    private final OneTimeCodeRepository oneTimeCodeRepository;
    private final TermsService termsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    // 일회용 코드 관련 상수
    private static final int ONE_TIME_CODE_EXPIRY_MINUTES = 1;

    /**
     * 로컬 로그인을 처리하고 토큰 정보를 포함한 DTO를 반환합니다.
     */
    public LoginResponseDto login(LoginRequestDto request) {
        User user = userRepository.findLoginableUser(request.getEmail(), Provider.LOCAL)
                .orElseThrow(() -> new TrainException(ErrorCode.LOGIN_FAILED));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new TrainException(ErrorCode.LOGIN_FAILED);
        }

        if (passwordEncoder.upgradeEncoding(user.getPassword())) {
            user.updatePassword(passwordEncoder.encode(request.getPassword()));
        }

        if (!user.isEmailVerified()) {
            throw new TrainException(ErrorCode.USER_EMAIL_NOT_VERIFIED);
        }

        JwtTokenProvider.JwtResponse tokens = jwtTokenProvider.generateTokens(user.getId(), user.getEmail());

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .token(tokens.refreshToken())
                .expiryDate(jwtTokenProvider.getExpiryDateTimeFromToken(tokens.refreshToken()))
                .build();
        refreshTokenRepository.save(refreshToken);

        user.updateLastLogin();
        if (!user.getStatus().isActive()) {
            user.updateStatus(UserStatus.ACTIVE);
        }

        return LoginResponseDto.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .accessToken(tokens.accessToken())
                .refreshToken(tokens.refreshToken())
                .build();
    }

    /**
     * Refresh Token을 사용하여 새로운 Access Token 및 Refresh Token을 발급합니다.
     * Refresh Token Rotation (RTR) 방식을 사용하여 보안을 강화합니다.
     *
     * @param refreshToken 현재 리프레시 토큰
     * @return 새로운 AccessToken과 RefreshToken
     */
    @Transactional
    public TokenRefreshResponseDto refreshAccessToken(String refreshToken) {
        RefreshToken storedToken = refreshTokenRepository.findByToken(refreshToken)
                .orElseThrow(() -> new TrainException(ErrorCode.REFRESH_TOKEN_INVALID));

        if (storedToken.isExpired()) {
            refreshTokenRepository.delete(storedToken);
            throw new TrainException(ErrorCode.REFRESH_TOKEN_INVALID, "만료된 리프레시 토큰입니다.");
        }

        Long userId = jwtTokenProvider.getUserIdFromToken(refreshToken);
        String email = jwtTokenProvider.getEmailFromToken(refreshToken);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new TrainException(ErrorCode.USER_NOT_FOUND));

        // 기존 토큰 삭제 (RTR 방식)
        refreshTokenRepository.delete(storedToken);
        refreshTokenRepository.flush(); // 즉시 DB에 반영하여 동시성 문제 방지

        // 새로운 AccessToken과 RefreshToken 생성
        JwtTokenProvider.JwtResponse newTokens = jwtTokenProvider.generateTokens(userId, email);

        // 새 RefreshToken을 DB에 저장
        saveRefreshToken(user, newTokens.refreshToken());

        log.info("토큰 갱신 완료 (RTR). User ID: {}", userId);

        return TokenRefreshResponseDto.builder()
                .accessToken(newTokens.accessToken())
                .refreshToken(newTokens.refreshToken())
                .build();
    }

    /**
     * 로그아웃을 처리합니다.
     */
    public void logout(String refreshToken) {
        refreshTokenRepository.findByToken(refreshToken)
                .ifPresent(refreshTokenRepository::delete);
    }

    /**
     * OAuth2 소셜 로그인 성공 후 사용자 정보를 처리합니다.
     */
    public SocialCallbackResponseDto processOAuth2User(String registrationId, OAuth2User oAuth2User) {
        Provider provider = Provider.fromRegistrationId(registrationId);
        SocialUserInfo userInfo = extractSocialUserInfo(provider, oAuth2User);

        Optional<SocialAccount> socialAccountOpt = socialAccountRepository
                .findByProviderAndProviderId(provider, userInfo.providerId());

        if (socialAccountOpt.isPresent()) {
            // --- 기존 회원인 경우 ---
            User user = socialAccountOpt.get().getUser();
            user.updateLastLogin();

            // DB 기반 일회용 코드 생성 및 저장
            String oneTimeCode = generateOneTimeCodeInDB(user.getId());
            log.info("기존 소셜 사용자 일회용 코드 생성 완료. User ID: {}, Code prefix: {}",
                    user.getId(), oneTimeCode.substring(0, 8) + "...");

            return SocialCallbackResponseDto.builder()
                    .isNewUser(false)
                    .oneTimeCode(oneTimeCode)
                    .email(user.getEmail())
                    .name(user.getName())
                    .provider(provider)
                    .build();
        } else {
            // --- 신규 회원인 경우 ---
            log.info("신규 소셜 사용자 확인 ({}). Email: {}", provider, LogMaskingUtil.maskEmail(userInfo.email()));

            // 1. 기존 미완료 레코드 삭제 (중복 방지)
            pendingSocialUserRepository.deleteByProviderAndProviderId(provider, userInfo.providerId());
            log.debug("기존 pending_social_users 레코드 정리 완료. Provider: {}, ProviderId: {}",
                    provider, LogMaskingUtil.maskToken(userInfo.providerId()));

            // 2. JWT 토큰 생성
            String pendingToken = jwtTokenProvider.generateSocialSignupPendingToken(
                    provider.name(), userInfo.providerId(), userInfo.email(), userInfo.name());

            // 3. 새 레코드 저장
            PendingSocialUser pendingUser = PendingSocialUser.builder()
                    .pendingToken(pendingToken)
                    .provider(provider)
                    .providerId(userInfo.providerId())
                    .email(userInfo.email())
                    .name(userInfo.name())
                    .expiryDate(jwtTokenProvider.getExpiryDateTimeFromToken(pendingToken))
                    .build();
            pendingSocialUserRepository.save(pendingUser);

            log.info("신규 소셜 사용자 대기 토큰 생성 완료. Email: {}, Provider: {}",
                    LogMaskingUtil.maskEmail(userInfo.email()), provider);

            return SocialCallbackResponseDto.builder()
                    .isNewUser(true)
                    .socialSignupPendingToken(pendingToken)
                    .email(userInfo.email())
                    .name(userInfo.name())
                    .provider(provider)
                    .build();
        }
    }

    /**
     * DB 기반 일회용 코드를 생성하고 저장합니다.
     *
     * @param userId 사용자 ID
     * @return 생성된 일회용 코드
     */
    private String generateOneTimeCodeInDB(Long userId) {
        // 해당 사용자의 기존 일회용 코드들을 모두 삭제 (중복 방지)
        oneTimeCodeRepository.deleteByUserId(userId.toString());

        String code = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        LocalDateTime expiryDate = LocalDateTime.now().plusMinutes(ONE_TIME_CODE_EXPIRY_MINUTES);

        OneTimeCode oneTimeCode = OneTimeCode.builder()
                .code(code)
                .userId(userId.toString())
                .expiryDate(expiryDate)
                .used(false)
                .build();

        oneTimeCodeRepository.save(oneTimeCode);

        log.debug("일회용 코드 생성 및 DB 저장 완료. Code: {}, User ID: {}, 만료시간: {}분",
                LogMaskingUtil.maskToken(code), userId, ONE_TIME_CODE_EXPIRY_MINUTES);

        return code;
    }

    /**
     * 일회용 코드를 AccessToken과 RefreshToken으로 교환합니다. (DB 기반)
     *
     * @param code 일회용 코드
     * @return LoginResponseDto (AccessToken, RefreshToken, 사용자 정보 포함)
     * @throws TrainException 코드가 유효하지 않거나 만료된 경우
     */
    @Transactional
    public LoginResponseDto exchangeCodeForTokens(String code) {
        OneTimeCode oneTimeCode = oneTimeCodeRepository.findByCode(code)
                .orElseThrow(() -> {
                    log.warn("일회용 코드 교환 실패 - 존재하지 않는 코드. Code: {}",
                            LogMaskingUtil.maskToken(code));
                    return new TrainException(ErrorCode.INVALID_ONE_TIME_CODE);
                });

        // 만료 또는 사용 여부 확인
        if (oneTimeCode.isExpired() || oneTimeCode.getUsed()) {
            oneTimeCodeRepository.delete(oneTimeCode);
            log.warn("일회용 코드 교환 실패 - 만료/이미 사용된 코드. Code: {}",
                    LogMaskingUtil.maskToken(code));
            throw new TrainException(ErrorCode.INVALID_ONE_TIME_CODE);
        }

        // 사용한 코드 즉시 삭제 (일회용 보장)
        oneTimeCodeRepository.delete(oneTimeCode);
        log.debug("사용한 일회용 코드 삭제 완료. User ID: {}", oneTimeCode.getUserId());

        Long userId = Long.parseLong(oneTimeCode.getUserId());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.error("일회용 코드 교환 실패 - 코드는 유효했으나 해당 User를 찾을 수 없음. User ID: {}", userId);
                    return new TrainException(ErrorCode.USER_NOT_FOUND);
                });

        // AccessToken과 RefreshToken 모두 생성
        JwtTokenProvider.JwtResponse tokens = jwtTokenProvider.generateTokens(user.getId(), user.getEmail());

        // RefreshToken을 DB에 저장
        saveRefreshToken(user, tokens.refreshToken());

        // 마지막 로그인 시간 업데이트
        user.updateLastLogin();

        log.info("일회용 코드 교환 성공. AccessToken 및 RefreshToken 발급. User ID: {}", userId);

        return LoginResponseDto.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .accessToken(tokens.accessToken())
                .refreshToken(tokens.refreshToken())
                .build();
    }

    /**
     * 일회용 코드를 AccessToken으로 교환합니다. (하위 호환성 유지용)
     * @deprecated exchangeCodeForTokens() 사용을 권장합니다.
     */
    @Deprecated
    @Transactional
    public String exchangeCodeForAccessToken(String code) {
        LoginResponseDto response = exchangeCodeForTokens(code);
        return response.getAccessToken();
    }

    /**
     * 만료된 일회용 코드들을 정기적으로 정리합니다.
     * 매 시간마다 실행됩니다.
     */
    @Scheduled(fixedRate = 3600000) // 1시간마다 실행
    @Transactional
    public void cleanupExpiredOneTimeCodes() {
        LocalDateTime now = LocalDateTime.now();
        int deletedCount = oneTimeCodeRepository.deleteByExpiryDateBefore(now);
        if (deletedCount > 0) {
            log.info("만료된 일회용 코드 정리 완료. 삭제된 코드 수: {}", deletedCount);
        }
    }

    /**
     * OAuth2User 객체에서 각 소셜 제공자에 맞는 사용자 정보를 추출합니다.
     */
    private SocialUserInfo extractSocialUserInfo(Provider provider, OAuth2User oAuth2User) {
        Map<String, Object> attributes = oAuth2User.getAttributes();
        return switch (provider) {
            case GOOGLE -> new SocialUserInfo(
                    (String) attributes.get("sub"),
                    (String) attributes.get("email"),
                    (String) attributes.get("name")
            );
            case KAKAO -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
                @SuppressWarnings("unchecked")
                Map<String, Object> profile = (Map<String, Object>) kakaoAccount.get("profile");
                
                // 카카오는 이메일 제공이 선택적이므로 없을 수 있음
                String email = kakaoAccount != null ? (String) kakaoAccount.get("email") : null;
                String nickname = profile != null ? (String) profile.get("nickname") : null;
                
                // 이메일이 없으면 providerId@kakao.temp 형식으로 임시 이메일 생성
                if (email == null || email.isBlank()) {
                    email = attributes.get("id") + "@kakao.temp";
                    log.warn("카카오 이메일 미제공 - 임시 이메일 생성: {}", LogMaskingUtil.maskEmail(email));
                }
                
                yield new SocialUserInfo(
                        String.valueOf(attributes.get("id")),
                        email,
                        nickname != null ? nickname : "카카오사용자"
                );
            }
            case NAVER -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> response = (Map<String, Object>) attributes.get("response");
                yield new SocialUserInfo(
                        (String) response.get("id"),
                        (String) response.get("email"),
                        (String) response.get("name")
                );
            }
            default -> throw new IllegalArgumentException("지원하지 않는 소셜 제공자입니다.");
        };
    }

    /**
     * Refresh Token을 DB에 저장하는 헬퍼 메서드
     */
    private void saveRefreshToken(User user, String token) {
        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .token(token)
                .expiryDate(jwtTokenProvider.getExpiryDateTimeFromToken(token))
                .build();
        refreshTokenRepository.save(refreshToken);
    }

    /**
     * 소셜 사용자 정보를 담기 위한 내부 레코드
     */
    private record SocialUserInfo(String providerId, String email, String name) {
    }

    /**
     * 소셜 회원가입의 마지막 단계를 처리합니다.
     */
    @Transactional
    public LoginResponseDto completeSocialSignup(SocialSignupCompleteRequestDto request) {
        String pendingToken = request.getSocialSignupPendingToken();
        jwtTokenProvider.validateToken(pendingToken);

        PendingSocialUser pendingUser = pendingSocialUserRepository.findByPendingToken(pendingToken)
                .orElseThrow(() -> new TrainException(ErrorCode.SOCIAL_SIGNUP_PENDING_TOKEN_INVALID));

        if (pendingUser.getUsed() || pendingUser.isExpired()) {
            throw new TrainException(ErrorCode.SOCIAL_SIGNUP_PENDING_TOKEN_INVALID);
        }

        if (request.isMinor()) throw new TrainException(ErrorCode.USER_AGE_RESTRICTION);
        if (!request.isJobDetailValid()) throw new TrainException(ErrorCode.JOB_DETAIL_REQUIRED);
        termsService.validateConsents(request.getConsents());

        User newUser = User.builder()
                .email(pendingUser.getEmail())
                .name(pendingUser.getName())
                .birthDate(request.getBirthDate())
                .jobType(request.getJobType())
                .jobDetail(request.getJobDetail())
                .primaryProvider(pendingUser.getProvider())
                .emailVerified(true)
                .build();

        SocialAccount socialAccount = SocialAccount.builder()
                .provider(pendingUser.getProvider())
                .providerId(pendingUser.getProviderId())
                .socialEmail(pendingUser.getEmail())
                .socialName(pendingUser.getName())
                .build();

        newUser.getSocialAccounts().add(socialAccount);
        socialAccount.setUser(newUser);

        userRepository.save(newUser);
        termsService.saveUserConsents(newUser, request.getConsents());

        pendingUser.markAsUsed();
        pendingSocialUserRepository.delete(pendingUser);

        // 신규 회원이므로 기존 토큰 삭제 불필요
        JwtTokenProvider.JwtResponse tokens = jwtTokenProvider.generateTokens(newUser.getId(), newUser.getEmail());
        saveRefreshToken(newUser, tokens.refreshToken());

        log.info("소셜 회원가입 및 로그인 완료. User ID: {}, Email: {}", newUser.getId(), newUser.getEmail());

        return LoginResponseDto.builder()
                .userId(newUser.getId())
                .email(newUser.getEmail())
                .name(newUser.getName())
                .accessToken(tokens.accessToken())
                .refreshToken(tokens.refreshToken())
                .build();
    }
}
