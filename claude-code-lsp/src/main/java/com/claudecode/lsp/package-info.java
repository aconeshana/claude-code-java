/**
 * LSP integration — manages LSP server subprocesses, JSON-RPC communication,
 * diagnostic registry/passive-feedback plumbing, and the {@code LSP} tool
 * exposed to the model. Configuration is read from enabled plugin manifests'
 * {@code lspServers} field (see {@link com.claudecode.lsp.LspServerSettings}).
 */
package com.claudecode.lsp;
