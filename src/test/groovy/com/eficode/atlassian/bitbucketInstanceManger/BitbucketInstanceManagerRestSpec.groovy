package com.eficode.atlassian.bitbucketInstanceManger

import com.eficode.atlassian.bitbucketInstanceManager.BitbucketInstanceManagerRest
import spock.lang.Shared
import spock.lang.Specification

/**
 * Presumes that the Bitbucket instance at baseUrl
 *      has been started for the first time ever
 *      bitbucketLicensePath points to a file containing a Bitbucket license
 *      baseUrl points to the url (and port) of bitbucket
 *
 *  Example script using devStack: https://github.com/eficode/devStack
 *
 *
 *
 import com.eficode.devstack.container.impl.BitbucketContainer
 BitbucketContainer bitbucketContainer = new BitbucketContainer(dockerHost, dockerCertPath)
 bitbucketContainer.stopAndRemoveContainer()
 bitbucketContainer.createContainer()
 bitbucketContainer.startContainer()
 */

class BitbucketInstanceManagerRestSpec extends Specification {

    @Shared
    static String baseUrl = "http://bitbucket.domain.se:7990"

    @Shared
    static String bitbucketLicensePath = "src/test/testResources/bitbucket/licenses/bitbucketLicense"

    @Shared
    static String restAdmin = "admin"

    @Shared
    static String restPw = "admin"


    BitbucketInstanceManagerRest setupBb() {

        return new BitbucketInstanceManagerRest(restAdmin, restPw, baseUrl)
    }

    String getBitbucketLicense() {
        return new File(bitbucketLicensePath).text
    }

    def testSetupOfBase() {

        setup:

        BitbucketInstanceManagerRest bb = setupBb()
        //bb.setApplicationProperties(bitbucketLicense, "Bitbucket", baseUrl)

        expect:
        //bb.status == "RUNNING"
        true



    }

}