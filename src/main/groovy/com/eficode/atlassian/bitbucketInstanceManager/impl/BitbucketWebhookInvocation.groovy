package com.eficode.atlassian.bitbucketInstanceManager.impl

import com.eficode.atlassian.bitbucketInstanceManager.model.WebhookEventType
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.databind.ObjectMapper

import com.google.gson.reflect.TypeToken

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


    static BitbucketWebhookInvocation fromJson(String jsonString) {

        BitbucketWebhookInvocation invocation = objectMapper.readValue(jsonString, BitbucketWebhookInvocation.class)

        return invocation

    }


}
