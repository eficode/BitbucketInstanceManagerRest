package com.eficode.atlassian.bitbucketInstanceManager.model

import com.fasterxml.jackson.annotation.JsonProperty

import java.lang.reflect.Field

enum MergeStrategy {

    @JsonProperty("no-ff")
    NO_FF,
    @JsonProperty("ff")
    FF,
    @JsonProperty("ff-only")
    FF_ONLY,
    @JsonProperty("rebase-no-ff")
    REBASE_NO_FF,
    @JsonProperty("rebase-ff-only")
    REBASE_FF_ONLY,
    @JsonProperty("squash")
    SQUASH,
    @JsonProperty("squash-ff-only")
    SQUASH_FF_ONLY,




    public String getSerializedName() {


        //https://clevercoder.net/2016/12/12/getting-annotation-value-enum-constant/
        Field f = this.getClass().getField(this.name())
        JsonProperty a = f.getAnnotation(JsonProperty.class)
        return a.value()

    }

    public static MergeStrategy getFromSerializedName(String inputName) {

        String inputNameParsed = inputName.toUpperCase().replaceAll(/\W/,"")
        return values().find {
            String availableStratName = it.serializedName.replaceAll(/-/, "").toUpperCase()
            if (availableStratName == inputNameParsed) {
                return it
            }
        }


    }




}


