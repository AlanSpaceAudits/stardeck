package com.spaceaudits.stardeck

import org.junit.Assume.assumeTrue
import org.junit.Test

class SeestarShareTest {
    // Live check against a scope: SEESTAR_IP=192.168.0.79 ./gradlew testDebugUnitTest --tests '*SeestarShareTest*'
    @Test
    fun uploadsAndRemovesAFile() {
        val ip = System.getenv("SEESTAR_IP")
        assumeTrue(ip != null)
        SeestarShare.upload(ip!!, "stardeck_share_test.txt", "test".byteInputStream())
        SeestarShare.delete(ip, "stardeck_share_test.txt")
    }
}
