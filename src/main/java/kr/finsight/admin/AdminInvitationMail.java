package kr.finsight.admin;

public interface AdminInvitationMail {
    void requireAdminInvitationAvailable(String email);
    void deliverAdminInvitation(String email, String link);
    void deliverAdminPasswordReset(String email, String link);
}
