package com.eficode.atlassian.bitbucketInstanceManager.model

import java.nio.charset.StandardCharsets

class BitbucketUser {

    String name
    String emailAddress


    String getProfileUrl(String baseUrl) {

        return baseUrl + "/users/" + URLEncoder.encode(name, StandardCharsets.UTF_8)
    }
}

