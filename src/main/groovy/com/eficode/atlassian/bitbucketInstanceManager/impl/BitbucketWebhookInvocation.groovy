package com.eficode.atlassian.bitbucketInstanceManager.impl

import com.eficode.atlassian.bitbucketInstanceManager.model.WebhookEventType
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.databind.ObjectMapper

import com.google.gson.reflect.TypeToken

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.lang.reflect.Type

/**
 * Represents an invocation of a Webhook
 */

class BitbucketWebhookInvocation {

    String id
    WebhookEventType event
    Integer duration
    Long start
    Long finish
    Request request
    Result result
    static ObjectMapper objectMapper = new ObjectMapper()

    class Request {
        String url
        LinkedHashMap headers
        String method
        String body
    }
    class Result {
        String description
        LinkedHashMap headers
        String outcome
        String body
        Integer statusCode
    }

    @JsonAnySetter
    Map<String, Object> dynamicValues = [:]


    /**
     * Checks if the Invocation has a correct signature <br>
     * Requires that a "Secret" has been setup for the webhook in bitbucket
     * @param secret The secret that the webhook is configured to use
     * @return true if a signature is present and valid
     */
    boolean hasValidSignature(String secret) {

        String invocationBody = request?.body
        String invocationSignature = request?.headers?.get("X-Hub-Signature")

        assert invocationBody : "Could not check for valid signature, webhook invocation has no body"
        assert invocationSignature : "Could not check for valid signature, webhook invocation is missing header: X-Hub-Signature"

        Mac mac = Mac.getInstance("HmacSHA256")
        SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(), "HmacSHA256")
        mac.init(secretKeySpec)
        byte[] digest = mac.doFinal(invocationBody.getBytes("UTF-8"))

        String calculatedSignature = digest.encodeHex().toString()


        return invocationSignature == "sha256=" + calculatedSignature
    }


    static BitbucketWebhookInvocation fromJson(String jsonString) {

        BitbucketWebhookInvocation invocation = objectMapper.readValue(jsonString, BitbucketWebhookInvocation.class)

        return invocation

    }


}
