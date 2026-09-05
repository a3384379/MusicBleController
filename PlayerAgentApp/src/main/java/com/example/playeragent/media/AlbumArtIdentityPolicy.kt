package com.example.playeragent.media

object AlbumArtIdentityPolicy {
    fun matches(
        expectedTitle: String,
        expectedArtist: String,
        actualTitle: String,
        actualArtist: String
    ): Boolean {
        val expectedTitleValue = expectedTitle.trim()
        if (expectedTitleValue.isEmpty()) {
            return true
        }

        val actualTitleValue = actualTitle.trim()
        if (!actualTitleValue.equals(expectedTitleValue, ignoreCase = true)) {
            return false
        }

        val expectedArtistValue = expectedArtist.trim()
        if (expectedArtistValue.isEmpty()) {
            return true
        }
        val actualArtistValue = actualArtist.trim()
        if (actualArtistValue.isEmpty()) {
            return false
        }
        return actualArtistValue.contains(expectedArtistValue, ignoreCase = true) ||
            expectedArtistValue.contains(actualArtistValue, ignoreCase = true)
    }
}
