package com.eficode.atlassian.bitbucketInstanceManager.entities

import groovy.json.JsonOutput
import kong.unirest.GenericType
import kong.unirest.JsonObjectMapper
import kong.unirest.Unirest
import kong.unirest.UnirestInstance
import unirest.shaded.com.google.gson.annotations.SerializedName

import java.lang.reflect.Type

class BitbucketRepo implements BitbucketEntity {



    String slug
    String id
    String name
    String hierarchyId
    String scmId
    String state
    String statusMessage
    boolean forkable
    BitbucketProject project

    @SerializedName("public")
    boolean isPublic
    boolean archived
    Map<String, ArrayList> links = ["clone": [[:]], "self": [[:]]]


    boolean isValid() {

        return slug && id && name && hierarchyId && project?.isValid()

    }

    String toString() {
        return project?.name + "/" + name
    }

    boolean equals(Object object) {

        return object instanceof BitbucketRepo && this.name == object.name && this.id == object.id
    }



    //A json string
    ArrayList<BitbucketRepo> fromJson(String rawJson) {


        GenericType type

        if (rawJson.startsWith("[")) {
            type = new GenericType<ArrayList<BitbucketRepo>>() {}

            return objectMapper.readValue(rawJson, type) as ArrayList<BitbucketRepo>
        } else if (rawJson.startsWith("{")) {
            type = new GenericType<BitbucketRepo>() {}
            return [objectMapper.readValue(rawJson, type)] as ArrayList<BitbucketRepo>
        } else {
            throw new InputMismatchException("Unexpected json format:" + rawJson.take(15))
        }


    }


}
