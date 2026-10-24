package app.opentv.data.provider.dizipal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DiziPalCodecTest {
    @Test
    fun decryptRmk_handlesEscapedBase64Slashes() {
        val raw = """
            {
              "ciphertext":"g6PzpOyXWAae5qyKr8kWGnN4EOTufn\/nEok\/h+THDSjuzvnH85jtvoKjYpD7zbC5sIawMY\/f2UCHJx3vnv9Y32rxoRQtDdbz7MDuj+1bKO0=",
              "iv":"e2d44a97d53b444a797cd012ee3119c6",
              "salt":"db508fff196795452874b5943df1139f3e661ec15837c254928317b920060c499f1a1aee25a26b9984b09b0e88939905cf04ff6511295ced121979804287f5cc1189d2aaef46651f4a4add051845ef7ff39ed934cd85b0d6894aad20c05becf965f72534c0530072a6f93d600aeb1314b7080954166a302c0a6bc7287955d5cce9d332fd14c76a6ffcb9f35b9d4b28091b1a044eca16985fb53220301b287eef6442474a348b05b2692e6b77d4976393aa1bff18f13197dbcdb9342565a41929a1837f75b715a6c43f38f0fafbd69c1513464d9b9fa0eb0175efde5b81f1f74574be18751b512c2b0a298906604066ad98f45693c4f3805fd9368e46e029de1b"
            }
        """.trimIndent()

        assertThat(DiziPalCodec.decryptRmk(raw))
            .isEqualTo("https://four.dplayer82.site/iframe.php?v=9abaeeaf0df4d6a4c53ecc72354e34d7")
    }
}
