package net.java21.blog.backend.user.dto;

import jakarta.validation.constraints.Size;

import net.java21.blog.backend.user.domain.User;

/**
 * {@code PATCH /me}(FR-008, FR-149, FR-153; JSON Merge Patch, api-guidelines 3절): 보낸 필드만 바꾼다.
 * 보냈는지 여부를 알아야 하므로 record 대신 setter가 표시를 남기는 클래스로 둔다.
 * <ul>
 *   <li>{@code nickname}: 1~30자(앞뒤 공백 제거), 지울 수 없다.</li>
 *   <li>{@code bio}: 300자까지, 비우거나 null이면 지운다.</li>
 *   <li>{@code locale}: ko·en·ja·zh-CN, null이면 미설정으로 되돌린다.</li>
 *   <li>{@code timeZone}: IANA ID, 지울 수 없다.</li>
 *   <li>{@code profileImageMediaKey}: 이미지 업로드(US4) 전까지는 받지 않는다.</li>
 * </ul>
 * 길이는 여기서 먼저 막고(400 {@code TOO_LONG}), 나머지 규칙은 {@code AccountService}가 확인한다.
 */
public class UpdateMeRequest {

    private String nickname;
    private boolean nicknamePresent;
    private String bio;
    private boolean bioPresent;
    private String profileImageMediaKey;
    private boolean profileImageMediaKeyPresent;
    private String locale;
    private boolean localePresent;
    private String timeZone;
    private boolean timeZonePresent;

    @Size(max = User.NICKNAME_MAX)
    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
        this.nicknamePresent = true;
    }

    @Size(max = User.BIO_MAX)
    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
        this.bioPresent = true;
    }

    public String getProfileImageMediaKey() {
        return profileImageMediaKey;
    }

    public void setProfileImageMediaKey(String profileImageMediaKey) {
        this.profileImageMediaKey = profileImageMediaKey;
        this.profileImageMediaKeyPresent = true;
    }

    @Size(max = 10)
    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
        this.localePresent = true;
    }

    @Size(max = 40)
    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
        this.timeZonePresent = true;
    }

    public boolean hasNickname() {
        return nicknamePresent;
    }

    public boolean hasBio() {
        return bioPresent;
    }

    public boolean hasProfileImageMediaKey() {
        return profileImageMediaKeyPresent;
    }

    public boolean hasLocale() {
        return localePresent;
    }

    public boolean hasTimeZone() {
        return timeZonePresent;
    }
}
