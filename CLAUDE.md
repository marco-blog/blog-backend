# backend

Blog Platform의 REST API 서버. 스펙은 형제 저장소 `../blog-docs/specs/`에 있고, 원칙은 `../blog-docs/.specify/memory/constitution.md`를 따른다.

## 스택
- 패키지: groupId `net.java21.blog`, artifactId `backend`, 기본 패키지 `net.java21.blog.backend`
- Java 21, Maven, Spring Boot 4.x, Spring Web, Spring Security, Spring Data JPA
- 인증: JWT Access Token(30분) + Refresh Token(사용 시 교체, 유휴 4시간·절대 7일)
- DB: MySQL 8(기본값), Flyway 마이그레이션
- 테스트: Spring 슬라이스 테스트 + JUnit 5 + Mockito + Testcontainers, 커버리지 JaCoCo

## 규칙
- 스펙(tasks.md)에 없는 기능은 구현하지 않는다.
- 패키지는 도메인별: `net.java21.blog.backend.{domain}/controller|service|repository|dto|domain`
- 에러 응답: `{ "code": "POST_NOT_FOUND", "message": "..." }`
- 쓰기 API는 소유자 검증 테스트를 함께 작성한다.
- 경로·기간 같은 운영 값은 하드코딩하지 않고 `@ConfigurationProperties`(접두어 `blog.`)로 관리한다. 예: `blog.media.upload-dir`, `blog.media.temp-dir`.

## 테스트 규칙
- 라인 커버리지 80% 이상. 미만이면 `./mvnw verify`가 실패한다(JaCoCo check).
- Controller: `@WebMvcTest(XxxController.class)` + MockMvc, 서비스는 `@MockitoBean`. 검증 실패, 401/403/404, 응답 JSON 형식을 확인한다.
- Service: Spring 컨텍스트 없이 `@ExtendWith(MockitoExtension.class)` 단위 테스트.
- Repository: `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + Testcontainers MySQL(`@ServiceConnection`).
- `@SpringBootTest`는 핵심 흐름 통합 확인에만 쓴다.
- Spring Boot 4 기준: `@MockBean` 대신 `@MockitoBean`을 쓴다.

## 명령 (앱 생성 후)
- 테스트: `./mvnw test`
- 테스트 + 커버리지 검사: `./mvnw verify`
- 실행: `./mvnw spring-boot:run`
