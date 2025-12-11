# 1. 베이스 이미지 선택 (Java 21 Corretto)
FROM amazoncorretto:21-alpine

# 2. 작업 디렉토리 설정
WORKDIR /app

# 3. JAR 파일 복사
# (CI/CD 7단계에서 EC2로 전송한 backend.jar 파일을
#  컨테이너 내부로 app.jar 라는 이름으로 복사)
COPY backend.jar app.jar

# 4. Spring Boot 포트 개방 (local .env의 SERVER_PORT=9090 )
EXPOSE 9090

# 5. 컨테이너 시작 시 앱 실행
ENTRYPOINT ["java", "-jar", "app.jar"]