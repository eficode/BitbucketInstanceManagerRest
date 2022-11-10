package com.eficode.atlassian.bitbucketInstanceManager

import com.google.gson.internal.LinkedTreeMap
import groovy.json.JsonOutput
import kong.unirest.JsonObjectMapper
import kong.unirest.Unirest
import kong.unirest.UnirestInstance

import java.lang.reflect.Field


trait BitbucketJsonEntity {


    static JsonObjectMapper objectMapper = Unirest.config().getObjectMapper() as JsonObjectMapper





    //An array of maps
    static ArrayList<BitbucketJsonEntity> fromJson(ArrayList<Map> rawJson, BitbucketInstanceManagerRest parent) {

        return fromJson(JsonOutput.toJson(rawJson), parent)
    }
    //A single map
    static ArrayList<BitbucketJsonEntity> fromJson(Map rawJson, BitbucketInstanceManagerRest parent) {


        return fromJson(JsonOutput.toJson(rawJson), parent)
    }


    abstract static ArrayList<BitbucketJsonEntity> fromJson(String rawJson, BitbucketInstanceManagerRest parent)




    void unOrphan(def parent) {

        Class clazz = this.getClass()
        Field thisField = clazz.declaredFields.find {it.name == "this\$0" }



        thisField.setAccessible(true)
        thisField.set(this, parent)
        thisField.setAccessible(false)


    }




    abstract boolean isValid()

    abstract String toString()

    abstract boolean equals(Object object)
}