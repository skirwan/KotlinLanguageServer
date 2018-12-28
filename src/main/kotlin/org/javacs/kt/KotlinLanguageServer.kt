package org.javacs.kt

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.javacs.kt.commands.ALL_COMMANDS
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture

data class LoadProgressReport(
  val message: String,
  val number: Int
)

interface KotlinLanguageClient : LanguageClient {
    @JsonNotification("language/loadProgressReport")
    fun loadProgressReport(progress: LoadProgressReport)
}

interface KotlinLanguageClientAware {
    fun connect(client: KotlinLanguageClient)
}

class KotlinLanguageServer : LanguageServer, KotlinLanguageClientAware {
    val classPath = CompilerClassPath()
    val sourcePath = SourcePath(classPath)
    val sourceFiles = SourceFiles(sourcePath)
    private val config = Configuration()
    private val textDocuments = KotlinTextDocumentService(sourceFiles, sourcePath, config)
    private var remoteClient: KotlinLanguageClient? = null
    private val workspaces = KotlinWorkspaceService(sourceFiles, sourcePath, classPath, textDocuments, config)
    
    override fun connect(client: KotlinLanguageClient) {
        remoteClient = client

        connectLoggingBackend(client)
        
        workspaces.connect(client)
        textDocuments.connect(client)
        
        LOG.info("Connected to client")
    }

    override fun shutdown(): CompletableFuture<Any> {
        return completedFuture(null)
    }

    override fun getTextDocumentService(): KotlinTextDocumentService {
        return textDocuments
    }

    override fun exit() {
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val capabilities = ServerCapabilities()
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental)
        capabilities.workspace = WorkspaceServerCapabilities()
        capabilities.workspace.workspaceFolders = WorkspaceFoldersOptions()
        capabilities.workspace.workspaceFolders.supported = true
        capabilities.workspace.workspaceFolders.changeNotifications = Either.forRight(true)
        capabilities.hoverProvider = true
        capabilities.completionProvider = CompletionOptions(false, listOf("."))
        capabilities.signatureHelpProvider = SignatureHelpOptions(listOf("(", ","))
        capabilities.definitionProvider = true
        capabilities.documentSymbolProvider = true
        capabilities.workspaceSymbolProvider = true
        capabilities.referencesProvider = true
        capabilities.codeActionProvider = true
        capabilities.executeCommandProvider = ExecuteCommandOptions(ALL_COMMANDS)

        if (params.rootUri != null) {
            remoteClient?.loadProgressReport(LoadProgressReport("Initializing workspace ${params.rootUri}", 0))

            LOG.info("Adding workspace {} to source path", params.rootUri)

            val root = Paths.get(URI.create(params.rootUri))

            remoteClient?.loadProgressReport(LoadProgressReport("Adding workspace roots...", 1))
            sourceFiles.addWorkspaceRoot(root)

            remoteClient?.loadProgressReport(LoadProgressReport("Adding workspace class paths...", 2))
            classPath.addWorkspaceRoot(root)
        }

        return completedFuture(InitializeResult(capabilities))
    }

    override fun getWorkspaceService(): KotlinWorkspaceService {
        return workspaces
    }
    
    private fun connectLoggingBackend(client: LanguageClient) {
        val backend: (LogMessage) -> Unit = {
            client.logMessage(MessageParams().apply {
                type = it.level.toLSPMessageType()
                message = it.message
            })
        }
        LOG.connectOutputBackend(backend)
        LOG.connectErrorBackend(backend)
    }
    
    private fun LogLevel.toLSPMessageType(): MessageType = when (this) {
        LogLevel.ERROR -> MessageType.Error
        LogLevel.WARN -> MessageType.Warning
        LogLevel.INFO -> MessageType.Info
        else -> MessageType.Log
    }
}
