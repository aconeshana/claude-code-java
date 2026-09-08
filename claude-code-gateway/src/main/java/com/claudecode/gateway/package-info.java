/**
 * In-process HTTP+SSE gateway: the third session endpoint after the Lanterna
 * TUI and the Session Link IM sidecar.
 *
 * <p>Design contract: the gateway never owns session semantics. It reuses the
 * runtime's authoritative {@code SessionHostRegistry}, the semantic
 * {@code SessionEventHub} stream, the idempotent
 * {@code SessionHostSubmissionLedger}, and the multi-endpoint
 * {@code InteractionCoordinator} arbitration, so TUI, IM, and web clients
 * observe the same live instance through the same code paths.
 */
package com.claudecode.gateway;
