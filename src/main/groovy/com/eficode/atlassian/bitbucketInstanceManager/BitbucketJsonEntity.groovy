package com.eficode.atlassian.bitbucketInstanceManager

import com.google.gson.internal.LinkedTreeMap
import groovy.json.JsonOutput
import kong.unirest.JsonObjectMapper
import kong.unirest.Unirest
import kong.unirest.UnirestInstance

import java.lang.reflect.Field

trait BitbucketJsonEntity {


    //UnirestInstance unirest
    //abstract static JsonObjectMapper objectMapper //= Unirest.config().getObjectMapper() as JsonObjectMapper
    static JsonObjectMapper objectMapper = Unirest.config().getObjectMapper() as JsonObjectMapper

    //abstract ArrayList<BitbucketJsonEntity> fromJson(ArrayList<Map> rawJson, UnirestInstance unirest)

    //abstract ArrayList<BitbucketJsonEntity> fromJson(Map rawJson, UnirestInstance unirest)



    //An array of maps
    static ArrayList<BitbucketJsonEntity> fromJson(ArrayList<Map> rawJson) {

        return fromJson(JsonOutput.toJson(rawJson))
    }
    //A single map
    static ArrayList<BitbucketJsonEntity> fromJson(Map rawJson) {


        return fromJson(JsonOutput.toJson(rawJson))
    }



    void unOrphan(BitbucketInstanceManagerRest parent) {

        Class clazz = this.getClass()
        Field thisField = clazz.declaredFields.find {it.name == "this\$0" }
        thisField.setAccessible(true)
        thisField.set(this, parent)
        thisField.setAccessible(false)


    }




    abstract static ArrayList<BitbucketJsonEntity> fromJson(String rawJson)

    abstract boolean isValid()

    abstract String toString()

    abstract boolean equals(Object object)
}