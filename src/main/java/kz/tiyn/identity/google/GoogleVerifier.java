package kz.tiyn.identity.google;

public interface GoogleVerifier {

    GoogleIdentity verify(String idToken);
}
