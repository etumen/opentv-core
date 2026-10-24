package app.opentv.data.provider.filmmakinesi

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Test

class FilmMakinesiProviderTest {
    @Test
    fun cardParser_readsMoviesAndSkipsSeries() {
        val html = """
            <a class="item" href="/film/test-film/" data-title="Test Film" data-score="7.4">
              <div class="thumbnail-outer"><img src="/poster/test.jpg"/></div>
              <div class="item-footer"><div class="info"><span>2026</span></div></div>
            </a>
            <a class="item" href="/dizi/test-dizi/" data-title="Test Dizi"></a>
        """.trimIndent()
        val provider = FilmMakinesiProvider(OkHttpClient())
        val items = provider.parseCards(Jsoup.parse(html, FilmMakinesiProvider.MAIN_URL))

        assertThat(items).hasSize(1)
        assertThat(items.single().title).isEqualTo("Test Film")
        assertThat(items.single().year).isEqualTo(2026)
        assertThat(items.single().rating).isEqualTo(7.4)
        assertThat(items.single().id).contains("/film/test-film/")
    }

    @Test
    fun cardParser_readsCurrentPosterLayout() {
        val html = """
            <a href="/colour-photo/" class="poster poster-slider">
              <div class="poster-wrapper">
                <img src="/poster/colour-photo.jpg" alt="Colour Photo" />
                <div class="poster-content">
                  <div class="poster-info">
                    <div class="poster-meta"><span>2020</span><span class="imdb">8.1</span></div>
                    <strong class="poster-title">Colour Photo</strong>
                  </div>
                </div>
              </div>
            </a>
        """.trimIndent()
        val provider = FilmMakinesiProvider(OkHttpClient())
        val items = provider.parseCards(Jsoup.parse(html, FilmMakinesiProvider.MAIN_URL))

        assertThat(items).hasSize(1)
        assertThat(items.single().title).isEqualTo("Colour Photo")
        assertThat(items.single().year).isEqualTo(2020)
        assertThat(items.single().rating).isEqualTo(8.1)
        assertThat(items.single().id).contains("/colour-photo/")
    }

    @Test
    fun categoryParser_readsCurrentGenreSection() {
        val html = """
            <section class="common-section">
              <div class="section-header">
                <h3 class="section-title">Türlerine Göre Filmler</h3>
              </div>
              <div class="section-content">
                <nav class="nav two-column">
                  <a class="nav-link" href="/aksiyon-filmleri-hd-izle/">Aksiyon</a>
                  <a class="nav-link" href="/korku-filmleri-hd-izle/">Korku</a>
                  <a class="nav-link" href="/bilim-kurgu-filmleri-hd-izle/">Bilim Kurgu</a>
                  <a class="nav-link" href="/komedi-fimleri-hd-izle/">Komedi</a>
                </nav>
              </div>
            </section>
        """.trimIndent()
        val provider = FilmMakinesiProvider(OkHttpClient())
        val categories = provider.parseCategoryLinks(
            Jsoup.parse(html, FilmMakinesiProvider.MAIN_URL),
        )

        assertThat(categories.map { it.first })
            .containsExactly("Aksiyon", "Korku", "Bilim Kurgu", "Komedi")
            .inOrder()
    }

    @Test
    fun paginationParser_buildsCurrentCategoryAndArchiveUrls() {
        val provider = FilmMakinesiProvider(OkHttpClient())

        val categoryDoc = Jsoup.parse(
            """
                <nav class="pagination-container"
                     data-pages="3"
                     data-page-action="category"></nav>
            """.trimIndent(),
            FilmMakinesiProvider.MAIN_URL + "/aksiyon-filmleri-hd-izle/",
        )
        val category = provider.parsePagination(categoryDoc)
        assertThat(category).isNotNull()
        assertThat(category?.pages).isEqualTo(3)
        assertThat(
            provider.paginationUrl(
                FilmMakinesiProvider.MAIN_URL + "/aksiyon-filmleri-hd-izle/",
                2,
                category!!,
            ),
        ).isEqualTo(
            FilmMakinesiProvider.MAIN_URL +
                "/aksiyon-filmleri-hd-izle/?knl_page_action=category&knl_paged=2",
        )

        val archiveDoc = Jsoup.parse(
            """
                <nav class="pagination-container"
                     data-pages="8"
                     data-page-action="archive"
                     data-sort="date"></nav>
            """.trimIndent(),
            FilmMakinesiProvider.MAIN_URL + "/film-arsivi/",
        )
        val archive = provider.parsePagination(archiveDoc)
        assertThat(archive).isNotNull()
        assertThat(
            provider.paginationUrl(
                FilmMakinesiProvider.MAIN_URL + "/film-arsivi/",
                2,
                archive!!,
            ),
        ).isEqualTo(
            FilmMakinesiProvider.MAIN_URL +
                "/film-arsivi/?knl_page_action=archive&knl_paged=2&knl_sort=date",
        )
    }

    @Test
    fun paginationParser_returnsNullWhenSiteHasOnlyOnePage() {
        val provider = FilmMakinesiProvider(OkHttpClient())
        val doc = Jsoup.parse(
            """<div class="posters-4-col"></div>""",
            FilmMakinesiProvider.MAIN_URL + "/animasyon-filmleri-hd-izle/",
        )

        assertThat(provider.parsePagination(doc)).isNull()
    }

    @Test
    fun categoryParser_readsLegacyFilmGenres() {
        val html = """
            <nav>
              <a href="/tur/aksiyon-fm1/film/">Aksiyon</a>
              <a href="/tur/korku-fm2/film/">Korku</a>
              <a href="/tur/bilim-kurgu-fm3/film/">Bilim Kurgu</a>
              <a href="/tur/komedi-fm1/film/">Komedi</a>
              <a href="/tur/dram-fm1/dizi/">Dram Dizi</a>
            </nav>
        """.trimIndent()
        val provider = FilmMakinesiProvider(OkHttpClient())
        val categories = provider.parseCategoryLinks(
            Jsoup.parse(html, FilmMakinesiProvider.MAIN_URL),
        )

        assertThat(categories.map { it.first })
            .containsExactly("Aksiyon", "Korku", "Bilim Kurgu", "Komedi")
            .inOrder()
    }
}
