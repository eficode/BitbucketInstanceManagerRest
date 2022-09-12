package com.eficode.atlassian.bitbucketInstanceManger

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import spock.lang.Shared
import spock.lang.Specification


class BitbucketInstanceManagerRestSpec extends Specification {

    @Shared
    static String baseUrl = "http://bitbucket.domain.se:7990"

    @Shared
    static String restAdmin = "admin"

    @Shared
    static String restPw = "admin"


    BitbucketInstanceManagerRest setupBb() {

        return new BitbucketInstanceManagerRest(restAdmin, restPw, baseUrl)
    }

    String getBitbucketLicense() {
        return new File("tests/testResources/bitbucket/licenses/bitbucketLicense").text
    }

    def testSetupOfBase() {

        setup:
        BitbucketInstanceManagerRest bb = setupBb()
        bb.setApplicationProperties(bitbucketLicense, "Bitbucket", baseUrl)

        expect:
        bb.status == "RUNNING"



    }

}