package kr.finsight.account;

import java.net.URI;

interface SocialProviderGateway {
    boolean configured(SocialAccounts.Provider provider);

    URI authorizationUrl(SocialAccounts.Provider provider, String state, boolean reauthenticate);

    SocialAccounts.VerifiedIdentity exchange(SocialAccounts.Provider provider, String state, String code);
}
