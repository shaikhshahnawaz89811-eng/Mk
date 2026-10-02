package com.codeassist.ai

import com.codeassist.ai.engine.Llm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlmParseTest {
    @Test fun parsesFileBlocksAndDropsUnsafePaths() {
        val reply = "### FILE: src/main.py\n```python\nprint(1)\n```\n" +
            "### FILE: ../evil.txt\n```\nx\n```\n" +
            "FILE: `index.html`\n```html\n<html></html>\n```\nSUMMARY: ok"
        val files = Llm.parseFileBlocks(reply)
        assertEquals(listOf("src/main.py", "index.html"), files.map { it.path })
        assertEquals("print(1)", files[0].content)
    }

    @Test fun firstCodeBlockToleratesMissingClosingFence() {
        assertEquals("a = 1", Llm.firstCodeBlock("text\n```python\na = 1\n"))
        assertNull(Llm.firstCodeBlock("no code here"))
    }
}
