package com.calyptra.app.blocklist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards the quality of the bundled category files (F11 / SOC-L1).
 *  Unit tests run with the module dir as working dir, so raw resources are
 *  read straight from the source tree. */
class CategoryDataLintTest {

    private val rawDir = File("src/main/res/raw")
    private val hostnameRegex = Regex("^(?!-)[a-z0-9-]{1,63}(?<!-)(\\.(?!-)[a-z0-9-]{1,63}(?<!-))+$")

    /** The small, hand-curated social files. NSFW is a large upstream bundle
     *  (HaGeZi, ~96k domains) with a different contract, asserted separately. */
    private val socialCategories = SocialCategory.entries.filter { it != SocialCategory.NSFW }

    private fun linesOf(category: SocialCategory): List<String> {
        val file = File(rawDir, "category_${category.key}.txt")
        assertTrue("missing ${file.path}", file.exists())
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
    }

    @Test
    fun `every category file exists and is non-empty`() {
        for (category in socialCategories) {
            assertTrue("${category.key} must have domains", linesOf(category).isNotEmpty())
        }
    }

    @Test
    fun `every line is a valid lowercase hostname`() {
        for (category in socialCategories) {
            for (line in linesOf(category)) {
                assertTrue(
                    "invalid hostname '$line' in category_${category.key}.txt",
                    hostnameRegex.matches(line)
                )
            }
        }
    }

    @Test
    fun `no duplicates within a category`() {
        for (category in socialCategories) {
            val lines = linesOf(category)
            assertTrue(
                "duplicates in category_${category.key}.txt",
                lines.size == lines.toSet().size
            )
        }
    }

    @Test
    fun `whatsapp is not part of the facebook category`() {
        // Parents blocking Facebook rarely intend to kill family WhatsApp.
        val facebook = linesOf(SocialCategory.FACEBOOK)
        assertFalse(facebook.any { it == "whatsapp.com" || it.endsWith(".whatsapp.com") })
        assertFalse(facebook.any { it == "whatsapp.net" || it.endsWith(".whatsapp.net") })
    }

    @Test
    fun `no youtube or google domains in any category`() {
        // YouTube control belongs to Restricted Mode (VPN-L3.6); blocking
        // google domains would break far more than social media.
        val forbidden = listOf("youtube.com", "googlevideo.com", "google.com", "googleapis.com")
        for (category in socialCategories) {
            for (line in linesOf(category)) {
                for (domain in forbidden) {
                    assertFalse(
                        "'$line' in category_${category.key}.txt touches $domain",
                        line == domain || line.endsWith(".$domain")
                    )
                }
            }
        }
    }

    @Test
    fun `total social category volume stays within the APK budget`() {
        val total = socialCategories.sumOf { linesOf(it).size }
        assertTrue("expected < 500 domains total, got $total", total < 500)
    }

    /** The NSFW bundle (nsfw.txt) is the large HaGeZi adult-content list. It has
     *  its own contract: present, non-trivial, credited, and lowercase domains. */
    @Test
    fun `nsfw bundle exists, is substantial, credited, and lowercase`() {
        val file = File(rawDir, "nsfw.txt")
        assertTrue("missing ${file.path}", file.exists())
        val all = file.readLines().map { it.trim() }.filter { it.isNotBlank() }
        val header = all.filter { it.startsWith("#") }.joinToString("\n").lowercase()
        assertTrue("nsfw.txt must credit HaGeZi", header.contains("hagezi"))
        assertTrue("nsfw.txt must record GPL-3.0", header.contains("gpl-3.0"))

        val domains = all.filter { !it.startsWith("#") }
        assertTrue("expected a substantial nsfw list, got ${domains.size}", domains.size > 1000)
        // Sample-validate format (the full list is too large to regex per line).
        for (line in domains.take(200) + domains.takeLast(200)) {
            assertEquals("nsfw entry must be lowercased: '$line'", line.lowercase(), line)
            assertTrue("nsfw entry must look like a hostname: '$line'", line.contains('.'))
        }
    }
}
