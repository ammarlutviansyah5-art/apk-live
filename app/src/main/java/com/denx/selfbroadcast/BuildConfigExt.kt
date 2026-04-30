package com.denx.selfbroadcast

object AppConfig {
    val agoraAppId: String get() = BuildConfig.AGORA_APP_ID
    val tokenServerUrl: String get() = BuildConfig.AGORA_TOKEN_SERVER_URL
    val emailLinkUrl: String get() = BuildConfig.EMAIL_LINK_URL
    val emailLinkDomain: String get() = BuildConfig.EMAIL_LINK_DOMAIN
}
