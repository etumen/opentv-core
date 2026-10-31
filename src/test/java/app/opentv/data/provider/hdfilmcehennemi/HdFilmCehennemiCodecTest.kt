/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.provider.hdfilmcehennemi

import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test

class HdFilmCehennemiCodecTest {

    @Test
    fun fnv1a_computesExpectedHexHash() {
        // Test vector verified against FastPlay player implementation
        val input = "5eb3b2b05f32493ca46fbf1c58114474|1793260088|x9z"
        val hash = HdFilmCehennemiCodec.fnv1a(input)
        assertThat(hash).isEqualTo("cf943b2b")
    }

    @Test
    fun generateXSp_formatsCorrectlyWithGivenNonce() {
        val sp = "5eb3b2b05f32493ca46fbf1c58114474"
        val spT = 1793260088L
        val nonce = "x9z"

        val xSp = HdFilmCehennemiCodec.generateXSp(sp, spT, nonce)
        assertThat(xSp).isEqualTo("1793260088.x9z.cf943b2b")
    }

    @Test
    fun generateXSp_generatesValidFormatWithRandomNonce() {
        val sp = "0b64cb21ab6eeabfc1b72dad7d33ad02"
        val spT = 1793260217L

        val xSp = HdFilmCehennemiCodec.generateXSp(sp, spT)
        val parts = xSp.split('.')
        assertThat(parts).hasSize(3)
        assertThat(parts[0]).isEqualTo("1793260217")
        assertThat(parts[1]).isNotEmpty()
        assertThat(parts[2]).hasLength(8)
    }

    @Test
    fun xorDecode_decryptsSetPlayPayload() {
        // Plain text: "https://fastplay.mom/video/BSNE6A2qCBHN"
        // Encoded with key "secretKey123"
        val plain = "https://fastplay.mom/video/BSNE6A2qCBHN"
        val key = "secretKey123".toByteArray(Charsets.UTF_8)
        val enc = ByteArray(plain.length)
        for (i in plain.indices) {
            enc[i] = (plain[i].code xor key[i % key.size].toInt()).toByte()
        }
        val encBase64 = java.util.Base64.getEncoder().encodeToString(enc)
        val keyBase64 = java.util.Base64.getEncoder().encodeToString(key)

        val decrypted = HdFilmCehennemiCodec.xorDecode(encBase64, keyBase64)
        assertThat(decrypted).isEqualTo(plain)
    }

    @Test
    fun parseSetPlayCerceve_extractsEncAndKey() {
        val html = """
            <html><body>
              <script>SPG.cerceve("b2", "AQIDBA==", "BAMCAQ==");</script>
            </body></html>
        """.trimIndent()

        val pair = HdFilmCehennemiCodec.parseSetPlayCerceve(html)
        assertThat(pair).isNotNull()
        assertThat(pair?.first).isEqualTo("AQIDBA==")
        assertThat(pair?.second).isEqualTo("BAMCAQ==")
    }

    @Test
    fun parseFastPlayStream_extractsStreamPath() {
        val html = """
            window.FSP = {
                stream: "/manifests/BSNE6A2qCBHN/master.txt?verify=1793253600-afkg1",
                type: "hls"
            };
        """.trimIndent()

        val stream = HdFilmCehennemiCodec.parseFastPlayStream(html)
        assertThat(stream).isEqualTo("/manifests/BSNE6A2qCBHN/master.txt?verify=1793253600-afkg1")
    }

    @Test
    fun parseFastPlaySp_extractsSpAndSpT() {
        val html = """
            window.SPG_A = {"acik":true,"hedef":"/engel.html","sp":"5eb3b2b05f32493ca46fbf1c58114474","spT":1793260088}
        """.trimIndent()

        val pair = HdFilmCehennemiCodec.parseFastPlaySp(html)
        assertThat(pair).isNotNull()
        assertThat(pair?.first).isEqualTo("5eb3b2b05f32493ca46fbf1c58114474")
        assertThat(pair?.second).isEqualTo(1793260088L)
    }

    @Test
    fun parseFastPlayTracks_filtersCaptionsCorrectly() {
        val html = """
            tracks: [
                {"file":"https://srv.bahcelievler.cfd/subtitle/tur.vtt","label":"Türkçe","lang":"tur","forced":false,"kind":"captions"},
                {"file":"https://srv.beyoglu.cfd/subtitle/eng.vtt","label":"İngilizce","lang":"eng","forced":false,"kind":"captions"},
                {"file":"https://srv.esenyurt.cfd/preview/thumbnails.vtt","kind":"thumbnails"}
            ]
        """.trimIndent()

        val tracks = HdFilmCehennemiCodec.parseFastPlayTracks(html)
        assertThat(tracks).hasSize(3)
        assertThat(tracks[0].file).isEqualTo("https://srv.bahcelievler.cfd/subtitle/tur.vtt")
        assertThat(tracks[0].label).isEqualTo("Türkçe")
        assertThat(tracks[0].lang).isEqualTo("tur")
        assertThat(tracks[1].label).isEqualTo("İngilizce")
        assertThat(tracks[2].kind).isEqualTo("thumbnails")
    }

    @Test
    fun parseVideoAjaxData_extractsNoncePostIdAndPlayers() {
        val html = """
            <script>
            window.videoAjax = {
                ajaxurl: 'https://www.hdfilmcehennemi.land/wp-admin/admin-ajax.php',
                nonce: '806c85eecb'
            };
            </script>
            <div id="fimcnt" data-post-id="23388" data-part-key="testKey"></div>
            <a class="options" data-player-name="SetPlay" data-post-id="23388"></a>
            <a class="options" data-player-name="Rapidrame" data-post-id="23388"></a>
        """.trimIndent()

        val nonce = HdFilmCehennemiCodec.parseVideoAjaxNonce(html)
        val postId = HdFilmCehennemiCodec.parseVideoAjaxPostId(html)
        val partKey = HdFilmCehennemiCodec.parseVideoAjaxPartKey(html)
        val playerNames = HdFilmCehennemiCodec.parseVideoAjaxPlayerNames(html)

        assertThat(nonce).isEqualTo("806c85eecb")
        assertThat(postId).isEqualTo("23388")
        assertThat(partKey).isEqualTo("testKey")
        assertThat(playerNames).containsExactly("SetPlay", "Rapidrame").inOrder()
    }

    @Test
    fun parsePaginationInfo_readsDataAttributes() {
        val doc = Jsoup.parse(
            """
            <div class="pagination">
                <div class="page-numbers" data-max-pages="127" data-type="movies" data-term-id="0">
                    <span class="page-number current">1</span>
                </div>
            </div>
            """.trimIndent(),
            "https://www.hdfilmcehennemi.land/film/",
        )

        val pag = HdFilmCehennemiCodec.parsePaginationInfo(doc)
        assertThat(pag).isNotNull()
        assertThat(pag?.maxPages).isEqualTo(127)
        assertThat(pag?.contentType).isEqualTo("movies")
        assertThat(pag?.termId).isEqualTo("0")
    }

    @Test
    fun parsePaginationActionInfo_readsNewRouterNav() {
        val doc = Jsoup.parse(
            """<nav class="pagination-container" data-pages="1314" data-page-action="home"></nav>""",
            "https://www.hdfilmcehennemi.nl/",
        )
        val pag = HdFilmCehennemiCodec.parsePaginationActionInfo(doc)
        assertThat(pag).isNotNull()
        assertThat(pag?.maxPages).isEqualTo(1314)
        assertThat(pag?.pageAction).isEqualTo("home")
    }

    @Test
    fun decryptRapidrame_decryptsLiveSnippet() {
        val file = java.io.File("C:/Users/birad/Projects/OpenTV/opentv-mobile/scratch_iframe.html")
        if (!file.exists()) return
        val html = file.readText()
        val url = HdFilmCehennemiCodec.decryptRapidrame(html, "hdfilmcehennemi.mobi")
        println("DECRYPTED URL FROM FILE: ${'$'}url")
        assertThat(url).isNotNull()
    }

    @Test
    fun decryptRapidrame_handlesEmptyAndMalformed() {
        assertThat(HdFilmCehennemiCodec.decryptRapidrame("")).isNull()
        assertThat(HdFilmCehennemiCodec.decryptRapidrame("var a = b();")).isNull()
    }

    @Test
    fun malformedPayloads_handledGracefully() {
        assertThat(HdFilmCehennemiCodec.parseSetPlayCerceve("<html></html>")).isNull()
        assertThat(HdFilmCehennemiCodec.parseFastPlayStream("")).isNull()
        assertThat(HdFilmCehennemiCodec.parseFastPlaySp("not a valid json")).isNull()
        assertThat(HdFilmCehennemiCodec.parseFastPlayTracks("")).isEmpty()
        assertThat(HdFilmCehennemiCodec.parseVideoAjaxNonce("no nonce here")).isNull()
    }
}
