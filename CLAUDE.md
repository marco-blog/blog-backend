# backend

Blog Platform의 REST API 서버. 스펙은 형제 저장소 `../docs/specs/`에 있고, 원칙은 `../docs/.specify/memory/constitution.md`를 따른다.

## 스택
- 패키지: groupId `net.java21.blog`, artifactId `backend`, 기본 패키지 `net.java21.blog.backend`
- Java 21, Maven, Spring Boot 4.x, Spring Web, Spring Security, Spring Data JPA
- 인증: JWT Access Token(30분) + Refresh Token(4시간)
- DB: MySQL 8(기본값), Flyway 마이그레이션
- 테스트: JUnit 5, Testcontainers

## 규칙
- 스펙(tasks.md)에 없는 기능은 구현하지 않는다.
- 패키지는 도메인별: `net.java21.blog.backend.{domain}/controller|service|repository|dto|domain`
- 에러 응답: `{ "code": "POST_NOT_FOUND", "message": "..." }`
- 쓰기 API는 소유자 검증 테스트를 함께 작성한다.

## 명령 (앱 생성 후)
- 테스트: `./mvnw test`
- 실행: `./mvnw spring-boot:run`
