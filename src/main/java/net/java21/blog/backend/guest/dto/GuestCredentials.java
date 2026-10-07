package net.java21.blog.backend.guest.dto;

/**
 * 검증을 마친 비회원 작성자 정보(004 research B6). 비밀번호는 BCrypt 해시로만 들고, IP는 저장할 때 암호화된다.
 *
 * @param name         정리한 이름(1~30자)
 * @param passwordHash BCrypt 해시
 * @param ip           작성 IP(개인정보, 로그에 남기지 않는다)
 */
public record GuestCredentials(String name, String passwordHash, String ip) {

    /** 로그에 비밀번호 해시·IP가 찍히지 않게 가린다. */
    @Override
    public String toString() {
        return "GuestCredentials[name=" + name + ", passwordHash=****, ip=****]";
    }
}
