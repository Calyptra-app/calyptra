package com.calyptra.app.blocklist

import org.junit.Assert.assertEquals
import org.junit.Test

class SocialCategoryTest {

    @Test
    fun `every category roundtrips through its key`() {
        for (category in SocialCategory.entries) {
            assertEquals(category, SocialCategory.fromKey(category.key))
        }
    }

    @Test
    fun `fromKeys drops unknown values`() {
        val parsed = SocialCategory.fromKeys(setOf("tiktok", "garbage", "", "myspace"))
        assertEquals(setOf(SocialCategory.TIKTOK), parsed)
    }

    @Test
    fun `fromKeys parses multiple valid keys`() {
        val parsed = SocialCategory.fromKeys(setOf("facebook", "discord"))
        assertEquals(setOf(SocialCategory.FACEBOOK, SocialCategory.DISCORD), parsed)
    }

    @Test
    fun `expected categories exist with stable keys`() {
        val expected = setOf(
            "tiktok", "instagram", "snapchat", "facebook",
            "twitter", "reddit", "discord", "twitch", "nsfw"
        )
        assertEquals(expected, SocialCategory.entries.map { it.key }.toSet())
    }

    @Test
    fun `nsfw category roundtrips and is a known key`() {
        assertEquals(SocialCategory.NSFW, SocialCategory.fromKey("nsfw"))
        assertEquals(setOf(SocialCategory.NSFW), SocialCategory.fromKeys(setOf("nsfw", "garbage")))
    }
}
