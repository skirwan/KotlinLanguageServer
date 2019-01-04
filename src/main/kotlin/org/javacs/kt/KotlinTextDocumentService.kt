package org.javacs.kt

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.CodeLensParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import org.javacs.kt.commands.JAVA_TO_KOTLIN_COMMAND
import org.javacs.kt.completion.completions
import org.javacs.kt.definition.goToDefinition
import org.javacs.kt.diagnostic.convertDiagnostic
import org.javacs.kt.hover.hoverAt
import org.javacs.kt.position.offset
import org.javacs.kt.position.range
import org.javacs.kt.references.findReferences
import org.javacs.kt.signaturehelp.fetchSignatureHelpAt
import org.javacs.kt.symbols.documentSymbols
import org.javacs.kt.util.AsyncExecutor
import org.javacs.kt.util.Debouncer
import org.javacs.kt.util.noResult
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.CompletableFuture

class KotlinTextDocumentService(
    private val sf: SourceFiles,
    private val sp: SourcePath,
    private val config: Configuration
) : TextDocumentService {
    private lateinit var client: LanguageClient
    private val async = AsyncExecutor()
    private var linting = false

    var debounceLint = Debouncer(Duration.ofMillis(config.debounceTime))
    val lintTodo = mutableSetOf<Path>()
    var lintCount = 0

    fun connect(client: LanguageClient) {
        this.client = client
    }

    private val TextDocumentIdentifier.filePath
        get() = Paths.get(URI.create(uri))

    private val TextDocumentIdentifier.content
        get() = sp.content(filePath)

    private fun recover(position: TextDocumentPositionParams, recompile: Boolean): Pair<CompiledFile, Int> {
        val file = position.textDocument.filePath
        val content = sp.content(file)
        val offset = offset(content, position.position.line, position.position.character)
        val compiled = if (recompile) sp.currentVersion(file) else sp.latestCompiledVersion(file)
        return Pair(compiled, offset)
    }

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> = async.compute {
        val actions = mutableListOf<Either<Command, CodeAction>>()

        params.context.diagnostics.mapNotNullTo(actions) {
            if(it.source == "kotlin") {
                when(it.code) {
                    "UNUSED_VARIABLE" -> {
                        val (file, cursor) = recover(TextDocumentPositionParams( params.textDocument, it.range.start), false)
                        val element = file.elementAtPoint(cursor)

                        when (element) {
                            is KtProperty -> {
                              Either.forRight<Command, CodeAction>(removeUnusedProperty(element, file, it, params))
                            }
                            else -> null
                        }
                    }
//                    "UNUSED_EXPRESSION" -> {
//                    }
                    else -> null
                }
            } else null
        }

      if(params.textDocument.filePath.fileName.endsWith(".java")){
        actions += Either.forLeft<Command, CodeAction>(
          Command("Convert Java code to Kotlin", JAVA_TO_KOTLIN_COMMAND, listOf(
            params.textDocument.uri,
            params.range
          ))
        )
      }

      actions
    }

  private fun removeUnusedProperty(element: KtProperty, file: CompiledFile, it: Diagnostic?, params: CodeActionParams): CodeAction {
    val replacement = if (element.hasInitializer()) {
      element.initializer?.let { rhs ->
        if (rhs is KtConstantExpression || rhs is KtReferenceExpression) {
          ""
        } else {
          rhs.text
        }
      } ?: ""
    } else ""

    val expressionRange = range(file.content, element.textRange)
    val editRange = if (replacement == "") {
      Range(expressionRange.start, Position(expressionRange.end.line + 1, 0))
    } else {
      expressionRange
    }

    val action = CodeAction("Remove unused variable")
    action.diagnostics = listOf(it)
    action.kind = "quickfix.removeUnused"
    action.edit = WorkspaceEdit(
      listOf(
        TextDocumentEdit(
          VersionedTextDocumentIdentifier(params.textDocument.uri, null),
          listOf(TextEdit(editRange, replacement))
        )
      )
    )
    return action
  }

  override fun hover(position: TextDocumentPositionParams): CompletableFuture<Hover?> = async.compute {
        reportTime {
            LOG.info("Hovering at {} {}:{}", position.textDocument.uri, position.position.line, position.position.character)

            val (file, cursor) = recover(position, true)
            hoverAt(file, cursor) ?: noResult("No hover found at ${describePosition(position)}", null)
        }
    }

    override fun documentHighlight(position: TextDocumentPositionParams): CompletableFuture<List<DocumentHighlight>> {
        TODO("not implemented")
    }

    override fun onTypeFormatting(params: DocumentOnTypeFormattingParams): CompletableFuture<List<TextEdit>> {
        TODO("not implemented")
    }

    override fun definition(position: TextDocumentPositionParams) = async.compute {
        reportTime {
            LOG.info("Go-to-definition at {}", describePosition(position))

            val (file, cursor) = recover(position, false)
            goToDefinition(file, cursor)?.let(::listOf) ?: noResult("Couldn't find definition at ${describePosition(position)}", emptyList())
        }
    }

    override fun rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture<List<TextEdit>> {
        TODO("not implemented")
    }

    override fun codeLens(params: CodeLensParams): CompletableFuture<List<CodeLens>> {
        TODO("not implemented")
    }

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit> {
        TODO("not implemented")
    }

    override fun completion(position: CompletionParams) = async.compute {
        reportTime {
            LOG.info("Completing at {}", describePosition(position))

            val (file, cursor) = recover(position, false)
            val completions = completions(file, cursor)

            LOG.info("Found {} items", completions.items.size)

            Either.forRight<List<CompletionItem>, CompletionList>(completions)
        }
    }

    override fun resolveCompletionItem(unresolved: CompletionItem): CompletableFuture<CompletionItem> {
        TODO("not implemented")
    }

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> = async.compute {
        LOG.info("Find symbols in {}", params.textDocument)

        reportTime {
            val path = params.textDocument.filePath
            val file = sp.parsedFile(path)
            documentSymbols(file)
        }
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val file = Paths.get(URI.create(params.textDocument.uri))

        sf.open(file, params.textDocument.text, params.textDocument.version)
        lintNow(file)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {}

    override fun signatureHelp(position: TextDocumentPositionParams): CompletableFuture<SignatureHelp?> = async.compute {
        reportTime {
            LOG.info("Signature help at {}", describePosition(position))

            val (file, cursor) = recover(position, false)
            fetchSignatureHelpAt(file, cursor) ?: noResult("No function call around ${describePosition(position)}", null)
        }
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val file = params.textDocument.filePath

        sf.close(file)
        clearDiagnostics(file)
    }

    override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> {
        TODO("not implemented")
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val file = params.textDocument.filePath

        sf.edit(file,  params.textDocument.version, params.contentChanges)
        lintLater(file)
    }

    override fun references(position: ReferenceParams) = async.compute {
        val file = Paths.get(URI.create(position.textDocument.uri))
        val content = sp.content(file)
        val offset = offset(content, position.position.line, position.position.character)
        findReferences(file, offset, sp)
    }

    override fun resolveCodeLens(unresolved: CodeLens): CompletableFuture<CodeLens> {
        TODO("not implemented")
    }

    private fun describePosition(position: TextDocumentPositionParams): String {
        val path = Paths.get(URI.create(position.textDocument.uri))
        return "${path.fileName} ${position.position.line + 1}:${position.position.character + 1}"
    }

    public fun updateDebouncer() {
        debounceLint = Debouncer(Duration.ofMillis(config.debounceTime))
    }

    private fun clearLint(): List<Path> {
        val result = lintTodo.toList()
        lintTodo.clear()
        return result
    }

    private fun lintLater(file: Path) {
        lintTodo.add(file)
        if (!linting) {
            debounceLint.submit(::doLint)
            linting = true
        }
    }

    private fun lintNow(file: Path) {
        lintTodo.add(file)
        debounceLint.submitImmediately(::doLint)
    }

    private fun doLint() {
        LOG.info("Linting {}", describeFiles(lintTodo))
        linting = true
        val files = clearLint()
        val context = sp.compileFiles(files)
        reportDiagnostics(files, context.diagnostics)
        lintCount++
        linting = false
    }

    private fun reportDiagnostics(compiled: Collection<Path>, kotlinDiagnostics: Diagnostics) {
        val langServerDiagnostics = kotlinDiagnostics.flatMap(::convertDiagnostic)
        val byFile = langServerDiagnostics.groupBy({ it.first }, { it.second })

        for ((file, diagnostics) in byFile) {
            if (sf.isOpen(file)) {
                client.publishDiagnostics(PublishDiagnosticsParams(file.toUri().toString(), diagnostics))

                LOG.info("Reported {} diagnostics in {}", diagnostics.size, file.fileName)
            }
            else LOG.info("Ignore {} diagnostics in {} because it's not open", diagnostics.size, file.fileName)
        }

        val noErrors = compiled - byFile.keys
        for (file in noErrors) {
            clearDiagnostics(file)

            LOG.info("No diagnostics in {}", file.fileName)
        }

        lintCount++
    }

    private fun clearDiagnostics(file: Path) {
        client.publishDiagnostics(PublishDiagnosticsParams(file.toUri().toString(), listOf()))
    }
}

private inline fun<T> reportTime(block: () -> T): T {
    val started = System.currentTimeMillis()
    try {
        return block()
    } finally {
        val finished = System.currentTimeMillis()
        LOG.info("Finished in {} ms", finished - started)
    }
}
