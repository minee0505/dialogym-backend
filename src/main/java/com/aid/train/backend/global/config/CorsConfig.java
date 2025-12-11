package com.aid.train.backend.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * CORS(Cross-Origin Resource Sharing) 설정 클래스입니다.
 * 프론트엔드 도메인에서만 API 호출을 허용합니다.
 * <p>
 * CORS(Cross-Origin Resource Sharing) 설정을 관리하는 Configuration 클래스
 *
 * <p>프론트엔드와 백엔드가 다른 포트 또는 도메인에서 실행될 때,
 * 브라우저의 Same-Origin Policy로 인해 요청이 차단되는 것을 방지하기 위해
 * 특정 Origin에서 오는 요청을 명시적으로 허용합니다.</p>
 * <p>
 * * <p>이 설정은 HTTP API 요청(/api/**)에만 적용되며,
 * * WebSocket 연결에 대해서는 별도의 설정이 필요합니다.</p>
 *
 * @author 왕택준
 * @author 진도희
 * @see WebMvcConfigurer
 * @since 1.0.0
 * @since 1.0
 */
@Slf4j
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origins}")
    private String[] allowedOrigins;

    @Value("${cors.allowed-methods}")
    private String[] allowedMethods;

    @Value("${cors.allowed-headers}")
    private String[] allowedHeaders;

//    @Value("${cors.exposed-headers}")
//    private String[] exposedHeaders;

    @Value("${cors.max-age:3600}")
    private Long maxAge;

    /**
     * 허용할 프론트엔드 URL
     * application.yml 또는 환경변수에서 주입받습니다.
     */
    @Value("${cors.allowed-origins}")
    private String origins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // 허용할 Origin (프론트엔드 도메인)
        // 예: http://localhost:5050, https://dialogym.shop, https://www.dialogym.shop
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins));

        // 허용할 HTTP 메서드
        configuration.setAllowedMethods(Arrays.asList(allowedMethods));

        // 허용할 헤더
        configuration.setAllowedHeaders(Arrays.asList(allowedHeaders));

        // 노출할 헤더 (프론트엔드에서 읽을 수 있는 헤더)
        //configuration.setExposedHeaders(Arrays.asList(exposedHeaders));

        // 인증 정보 포함 허용 (쿠키 전송)
        configuration.setAllowCredentials(true);

        // Preflight 요청 캐시 시간 (초)
        configuration.setMaxAge(maxAge);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }

    /**
     * CORS 매핑 설정을 추가합니다.
     *
     * <p>설정 내용:</p>
     * <ul>
     *   <li>허용 경로: /api/** (모든 API 엔드포인트)</li>
     *   <li>허용 Origin: 환경변수로 지정된 프론트엔드 URL</li>
     *   <li>허용 헤더: 모든 헤더</li>
     *   <li>허용 메서드: 모든 HTTP 메서드 (GET, POST, PUT, DELETE 등)</li>
     *   <li>인증 정보: 쿠키 및 인증 헤더 포함 허용</li>
     * </ul>
     *
     * @param registry CORS 설정을 등록할 레지스트리
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {

        String[] permitUrls = origins.split(",");

        registry
                .addMapping("/api/**") // 클라이언트의 어떤 요청을 허용할지
                .allowedOrigins(permitUrls) // 어떤 Origin을 허용할지
                .allowedHeaders("*") // 어떤 헤더를 허용할지
                .allowedMethods("*") // 어떤 요청방식을 허용할지
                .allowCredentials(true); // 보안쿠키를 허용할지
    }
}