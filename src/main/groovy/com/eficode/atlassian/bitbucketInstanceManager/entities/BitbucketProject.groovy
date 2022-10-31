package com.eficode.atlassian.bitbucketInstanceManager.entities

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import kong.unirest.GenericType
import kong.unirest.JsonObjectMapper
import kong.unirest.Unirest
import kong.unirest.UnirestInstance
import org.apache.groovy.json.internal.LazyMap
import unirest.shaded.com.google.gson.annotations.SerializedName
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable

class BitbucketProject implements BitbucketEntity {



    String key
    String id
    String name
    String type
    Map<String, ArrayList> links

    @SerializedName("public")
    boolean isPublic


    boolean equals(Object object) {

        return object instanceof BitbucketProject && this.key == object.key && this.id == object.id
    }

    boolean isValid() {

        return key && id && name && type

    }


    //A json string
    ArrayList<BitbucketProject> fromJson(String rawJson ) {

        this.unirest = unirest

        GenericType type

        if (rawJson.startsWith("[")) {
            type = new GenericType<ArrayList<BitbucketProject>>() {}
            return objectMapper.readValue(rawJson, type).findAll {it.isValid()} as ArrayList<BitbucketProject>
        } else if (rawJson.startsWith("{")) {
            type = new GenericType<BitbucketProject>() {}
            return [objectMapper.readValue(rawJson, type)].findAll {it.isValid()} as ArrayList<BitbucketProject>
        } else {
            throw new InputMismatchException("Unexpected json format:" + rawJson.take(15))
        }


    }
}
