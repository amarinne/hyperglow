package com.eza.hyperglow.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SpicyResponseCreditDecodeTest {
    @Test fun olderDocumentsHaveNoCredit() {
        assertEquals("", decodeSpicyResponseCredit(Json.parseToJsonElement("{}").jsonObject))
    }

    @Test fun responseCreditKeepsItsDisplayText() {
        val root = Json.parseToJsonElement("""{"responseCredit":"Lyrics from Spicy Lyrics\nuploaded by Uploader, made by Maker"}""").jsonObject
        assertEquals("Lyrics from Spicy Lyrics\nuploaded by Uploader, made by Maker", decodeSpicyResponseCredit(root))
    }

    @Test(expected = IllegalArgumentException::class) fun oversizedCreditCannotEnterDocumentStore() {
        decodeSpicyResponseCredit(Json.parseToJsonElement("""{"responseCredit":"${"x".repeat(8193)}"}""").jsonObject)
    }
}
