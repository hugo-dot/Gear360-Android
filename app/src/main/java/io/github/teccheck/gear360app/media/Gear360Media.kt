package io.github.teccheck.gear360app.media

import java.util.Date

data class Gear360Media(
    val id: String,
    val name: String,
    val type: Gear360MediaType,
    val remoteUrl: String?,
    val thumbnailUrl: String? = null,
    val size: Long? = null,
    val date: Date? = null
)

enum class Gear360MediaType {
    PHOTO,
    VIDEO,
    UNKNOWN;

    companion object {
        fun fromName(name: String): Gear360MediaType {
            return when (name.substringAfterLast('.', "").lowercase()) {
                "jpg", "jpeg" -> PHOTO
                "mp4" -> VIDEO
                else -> UNKNOWN
            }
        }
    }
}
