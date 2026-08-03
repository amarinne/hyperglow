package com.eza.hyperglow.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class UiStringsContractTest {
    @Test
    fun simplifiedChineseAndContributionTemplateCoverEveryDefaultString() {
        val defaults = names("src/main/res/values/strings.xml")
        val chinese = names("src/main/res/values-zh-rCN/strings.xml")
        val template = names("translation/strings-template.xml")

        assertTrue(defaults.isNotEmpty())
        assertEquals(defaults, chinese)
        assertEquals(defaults, template)
    }

    private fun names(path: String): Set<String> {
        var file = File(path)
        if (!file.isFile) file = File("app/$path")
        assertTrue("Missing ${file.absolutePath}", file.isFile)
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file).getElementsByTagName("string")
        return buildSet {
            for (index in 0 until nodes.length) {
                add((nodes.item(index) as Element).getAttribute("name"))
            }
        }
    }
}
