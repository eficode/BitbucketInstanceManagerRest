package com.eficode.atlassian.bitbucketInstanceManagerOld.entities

import groovy.json.JsonOutput
import kong.unirest.JsonObjectMapper
import kong.unirest.Unirest
import kong.unirest.UnirestInstance

trait BitbucketEntity  {

    UnirestInstance unirest
    static JsonObjectMapper objectMapper = Unirest.config().getObjectMapper() as JsonObjectMapper


    //An array of maps
    ArrayList<BitbucketEntity> fromJson(ArrayList<Map> rawJson , UnirestInstance unirest) {

        return fromJson(JsonOutput.toJson(rawJson), unirest)
    }

    //A single map
    ArrayList<BitbucketEntity> fromJson(Map rawJson, UnirestInstance unirest) {

        return fromJson(JsonOutput.toJson(rawJson), unirest)
    }

    abstract ArrayList<BitbucketEntity> fromJson(String rawJson)

    abstract boolean isValid()
    abstract String toString()
    abstract boolean equals(Object object)
}