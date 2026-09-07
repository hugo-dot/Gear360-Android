package io.github.teccheck.gear360app.media

import org.junit.Assert.assertEquals
import org.junit.Test

class Gear360MediaTest {
    @Test
    fun mediaTypeIsInferredFromFileName() {
        assertEquals(Gear360MediaType.PHOTO, Gear360MediaType.fromName("SAM_0001.JPG"))
        assertEquals(Gear360MediaType.PHOTO, Gear360MediaType.fromName("SAM_0002.jpeg"))
        assertEquals(Gear360MediaType.VIDEO, Gear360MediaType.fromName("SAM_0003.MP4"))
        assertEquals(Gear360MediaType.UNKNOWN, Gear360MediaType.fromName("manifest.json"))
    }

    @Test
    fun mediaModelStoresRemoteAndThumbnailUrls() {
        val media = Gear360Media(
            id = "SAM_0001.JPG",
            name = "SAM_0001.JPG",
            type = Gear360MediaType.PHOTO,
            remoteUrl = "http://192.168.107.1/DCIM/100PHOTO/SAM_0001.JPG",
            thumbnailUrl = "http://192.168.107.1/thumb/SAM_0001.JPG",
            size = 1024L
        )

        assertEquals("SAM_0001.JPG", media.id)
        assertEquals(Gear360MediaType.PHOTO, media.type)
        assertEquals(1024L, media.size)
    }
}
