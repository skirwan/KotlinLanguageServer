package org.javacs.kt

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.junit.Assert.assertThat
import org.junit.Test

class QuickFixTest : SingleFileTestFixture("quickfix", "UnusedVariable.kt") {
    @Test
    fun `Ensure constant initializer is removed`() {
        // We're looking for: val a = 10

        // NB: codeActionParams and the rest of the test harness uses 1-based line numbering; LSP uses 0-based line numbering
        val actions = languageServer.textDocumentService.codeAction(codeActionParams(file, 2, 9)).get()!!

        val expectedAction = actions.firstOrNull() { it.isRight && it.right.kind == "quickfix.removeUnused" }?.right

        assertThat(expectedAction, not(nullValue()))
        assertThat(expectedAction!!.edit, not(nullValue()))
        assertThat(expectedAction.edit.documentChanges, not(nullValue()))
        assertThat(expectedAction.edit.documentChanges.count(), equalTo(1))
        assertThat(expectedAction.edit.documentChanges[0].edits, not(nullValue()))
        assertThat(expectedAction.edit.documentChanges[0].edits.count(), equalTo(1))

        val edit = expectedAction.edit.documentChanges[0].edits[0]

        // Remove the whole line
        assertThat(edit.newText, equalTo(""))
        assertThat(edit.range, equalTo(
            Range(
                Position(1, 0),
                Position(2, 0)
            )
        ))
    }

    @Test
    fun `Ensure string initializer is removed`() {
        // We're looking for: val b = "something"

        // NB: codeActionParams and the rest of the test harness uses 1-based line numbering; LSP uses 0-based line numbering
        val actions = languageServer.textDocumentService.codeAction(codeActionParams(file, 3, 9)).get()!!

        val expectedAction = actions.firstOrNull() { it.isRight && it.right.kind == "quickfix.removeUnused" }?.right

        assertThat(expectedAction, not(nullValue()))
        assertThat(expectedAction!!.edit, not(nullValue()))
        assertThat(expectedAction.edit.documentChanges, not(nullValue()))
        assertThat(expectedAction.edit.documentChanges.count(), equalTo(1))
        assertThat(expectedAction.edit.documentChanges[0].edits, not(nullValue()))
        assertThat(expectedAction.edit.documentChanges[0].edits.count(), equalTo(1))

        val edit = expectedAction.edit.documentChanges[0].edits[0]

        // Remove the whole line
        assertThat(edit.newText, equalTo(""))
        assertThat(edit.range, equalTo(
            Range(
                Position(2, 0),
                Position(3, 0)
            )
        ))
    }

    @Test
    fun `Ensure initializer with side effect is retained`() {
        // We're looking for: val c = computeWithSideEffect()

        val actions = languageServer.textDocumentService.codeAction(codeActionParams(file, 4, 9)).get()!!

        val expectedAction = actions.firstOrNull() { it.isRight && it.right.kind == "quickfix.removeUnused" }?.right

        assertThat(expectedAction, not(nullValue()))
        assertThat(expectedAction!!.edit, not(nullValue()))
        assertThat(expectedAction.edit.documentChanges, not(nullValue()))
        assertThat(expectedAction.edit.documentChanges.count(), equalTo(1))
        assertThat(expectedAction.edit.documentChanges[0].edits, not(nullValue()))
        assertThat(expectedAction.edit.documentChanges[0].edits.count(), equalTo(1))

        val edit = expectedAction.edit.documentChanges[0].edits[0]

        // Replace 'val c = computeWithSideEffect()' with just 'computeWithSideEffect()'
        assertThat(edit.newText, equalTo("computeWithSideEffect()"))
        assertThat(edit.range, equalTo(
            Range(
                Position(3, 4),
                Position(3, 35)
            )
        ))
    }

    @Test
    fun `Ensure constant initializer with trailing semicolon is removed`() {
        // We're looking for: val d = 10;

        val actions = languageServer.textDocumentService.codeAction(codeActionParams(file, 5, 9)).get()!!

        val expectedAction = actions.firstOrNull() { it.isRight && it.right.kind == "quickfix.removeUnused" }?.right

        assertThat(expectedAction, not(nullValue()))
        assertThat(expectedAction!!.edit, not(nullValue()))
        assertThat(expectedAction.edit.documentChanges, not(nullValue()))
        assertThat(expectedAction.edit.documentChanges.count(), equalTo(1))
        assertThat(expectedAction.edit.documentChanges[0].edits, not(nullValue()))
        assertThat(expectedAction.edit.documentChanges[0].edits.count(), equalTo(1))

        val edit = expectedAction.edit.documentChanges[0].edits[0]

        // Remove the whole line
        assertThat(edit.newText, equalTo(""))
        assertThat(edit.range, equalTo(
            Range(
                Position(4, 0),
                Position(5, 0)
            )
        ))
    }

    @Test
    fun `Ensure variable on same line as another variable retains other variable`() {
        // We're looking for: val e1 = 10; val e2 = "Something"

        val actions = languageServer.textDocumentService.codeAction(codeActionParams(file, 6, 9)).get()!!

        val expectedAction = actions.firstOrNull() { it.isRight && it.right.kind == "quickfix.removeUnused" }?.right

        assertThat(expectedAction, not(nullValue()))
        assertThat(expectedAction!!.edit, not(nullValue()))
        assertThat(expectedAction.edit.documentChanges, not(nullValue()))
        assertThat(expectedAction.edit.documentChanges.count(), equalTo(1))
        assertThat(expectedAction.edit.documentChanges[0].edits, not(nullValue()))
        assertThat(expectedAction.edit.documentChanges[0].edits.count(), equalTo(1))

        val edit = expectedAction.edit.documentChanges[0].edits[0]

        // Remove the whole line
        assertThat(edit.newText, equalTo(""))
        assertThat(edit.range, equalTo(
            Range(
                Position(5, 4),
                Position(5, 17)
            )
        ))
    }

    @Test
    fun `Ensure second variable on line retains first variable`() {
        // We're looking for: val e1 = 10; val e2 = "Something"

        val actions = languageServer.textDocumentService.codeAction(codeActionParams(file, 6, 22)).get()!!

        val expectedAction = actions.firstOrNull() { it.isRight && it.right.kind == "quickfix.removeUnused" }?.right

        assertThat(expectedAction, not(nullValue()))
        assertThat(expectedAction!!.edit, not(nullValue()))
        assertThat(expectedAction.edit.documentChanges, not(nullValue()))
        assertThat(expectedAction.edit.documentChanges.count(), equalTo(1))
        assertThat(expectedAction.edit.documentChanges[0].edits, not(nullValue()))
        assertThat(expectedAction.edit.documentChanges[0].edits.count(), equalTo(1))

        val edit = expectedAction.edit.documentChanges[0].edits[0]

        // Remove the whole line
        assertThat(edit.newText, equalTo(""))
        assertThat(edit.range, equalTo(
            Range(
                Position(5, 15),
                Position(5, 37)
            )
        ))
    }
}
