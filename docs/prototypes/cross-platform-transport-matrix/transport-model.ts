/* THROWAWAY PROTOTYPE — host model only, not real transport or production code. */

type Route = "LAN" | "WEBRTC_DIRECT" | "TURN" | "MAILBOX";
type RouteHealth = "AVAILABLE" | "FAILED" | "PERMISSION_DENIED" | "OFFLINE";
type MailboxHealth = "HEALTHY" | "AUTH_FAILED" | "QUOTA" | "ROLLBACK";

interface Obligation {
  readonly operationId: string;
  peerAck: boolean;
  readonly protectedBy: Set<string>;
  receipts: number;
}

interface Snapshot {
  readonly selectedLiveRoute: Route | null;
  readonly pendingOperations: number;
  readonly peerRedundant: number;
  readonly protected: Readonly<Record<string, number>>;
  readonly workerGeneration: number;
  readonly offscreenGeneration: number;
  readonly retryGeneration: number;
  readonly diagnostics: readonly string[];
}

class TransportCoordinator {
  readonly #routes = new Map<Route, RouteHealth>([
    ["LAN", "OFFLINE"], ["WEBRTC_DIRECT", "OFFLINE"], ["TURN", "OFFLINE"], ["MAILBOX", "OFFLINE"],
  ]);
  readonly #mailboxes = new Map<string, MailboxHealth>();
  readonly #outbox = new Map<string, Obligation>();
  readonly #diagnostics: string[] = [];
  #workerGeneration = 1;
  #offscreenGeneration = 1;
  #retryGeneration = 0;

  enqueue(operationId: string): void {
    if (!this.#outbox.has(operationId)) {
      this.#outbox.set(operationId, { operationId, peerAck: false, protectedBy: new Set(), receipts: 0 });
      this.#diagnostics.unshift(`${operationId}: saved locally with durable delivery obligation`);
    }
  }

  configureMailbox(name: string): void {
    this.#mailboxes.set(name, "HEALTHY");
    this.#routes.set("MAILBOX", "AVAILABLE");
  }

  observeRoute(route: Route, health: RouteHealth): void {
    this.#routes.set(route, health);
    this.#diagnostics.unshift(`${route}: ${health}`);
  }

  recordReceipt(operationId: string, route: Route): void {
    const obligation = this.#required(operationId);
    obligation.receipts += 1;
    this.#diagnostics.unshift(`${operationId}: ${route} receipt is not durability`);
  }

  recordDurablePeerAck(operationId: string): void {
    const obligation = this.#required(operationId);
    obligation.peerAck = true;
    this.#diagnostics.unshift(`${operationId}: peer-redundant after signed durable frontier acknowledgment`);
  }

  recordMailboxProof(operationId: string, mailbox: string): void {
    const obligation = this.#required(operationId);
    if (this.#mailboxes.get(mailbox) !== "HEALTHY") throw new Error(`${mailbox} cannot prove immutable storage`);
    obligation.protectedBy.add(mailbox);
    this.#diagnostics.unshift(`${operationId}: protected independently by ${mailbox}`);
  }

  failMailbox(mailbox: string, health: Exclude<MailboxHealth, "HEALTHY">): void {
    this.#mailboxes.set(mailbox, health);
    for (const obligation of this.#outbox.values()) obligation.protectedBy.delete(mailbox);
    this.#diagnostics.unshift(`${mailbox}: ${health}; no domain deletion inferred`);
  }

  rotateCredential(mailbox: string): void {
    this.#mailboxes.set(mailbox, "HEALTHY");
    this.#retryGeneration += 1;
    this.#diagnostics.unshift(`${mailbox}: credential rotated; protection requires fresh verification`);
  }

  loseWorker(): void {
    this.#workerGeneration += 1;
    this.#diagnostics.unshift("MV3 worker lost; durable outbox retained");
  }

  loseOffscreen(): void {
    this.#offscreenGeneration += 1;
    this.#routes.set("WEBRTC_DIRECT", "OFFLINE");
    this.#routes.set("TURN", "OFFLINE");
    this.#diagnostics.unshift("Offscreen document lost; live sessions discarded, durable outbox retained");
  }

  wake(): void {
    this.#retryGeneration += 1;
    this.#diagnostics.unshift("Wake scheduled a bounded drain from durable state");
  }

  snapshot(): Snapshot {
    const preference: Route[] = ["LAN", "WEBRTC_DIRECT", "TURN"];
    const selectedLiveRoute = preference.find((route) => this.#routes.get(route) === "AVAILABLE") ?? null;
    const protectedCounts: Record<string, number> = {};
    for (const mailbox of this.#mailboxes.keys()) {
      protectedCounts[mailbox] = [...this.#outbox.values()].filter((item) => item.protectedBy.has(mailbox)).length;
    }
    return {
      selectedLiveRoute,
      pendingOperations: [...this.#outbox.values()].filter((item) => !item.peerAck).length,
      peerRedundant: [...this.#outbox.values()].filter((item) => item.peerAck).length,
      protected: protectedCounts,
      workerGeneration: this.#workerGeneration,
      offscreenGeneration: this.#offscreenGeneration,
      retryGeneration: this.#retryGeneration,
      diagnostics: this.#diagnostics.slice(0, 8),
    };
  }

  #required(operationId: string): Obligation {
    const obligation = this.#outbox.get(operationId);
    if (!obligation) throw new Error(`unknown operation ${operationId}`);
    return obligation;
  }
}

function compact(name: string, snapshot: Snapshot): string {
  const protectedSummary = Object.entries(snapshot.protected).map(([key, value]) => `${key}:${value}`).join(",") || "none";
  return `${name}|route=${snapshot.selectedLiveRoute ?? "NONE"}|pending=${snapshot.pendingOperations}|peer=${snapshot.peerRedundant}|protected=${protectedSummary}|worker=${snapshot.workerGeneration}|offscreen=${snapshot.offscreenGeneration}|retry=${snapshot.retryGeneration}`;
}

function main(): void {
  const fallback = new TransportCoordinator();
  fallback.enqueue("op-a");
  fallback.configureMailbox("dav-a");
  fallback.observeRoute("LAN", "PERMISSION_DENIED");
  fallback.observeRoute("WEBRTC_DIRECT", "FAILED");
  fallback.observeRoute("TURN", "AVAILABLE");
  fallback.recordReceipt("op-a", "TURN");
  console.log(compact("turn-receipt", fallback.snapshot()));
  fallback.recordDurablePeerAck("op-a");
  fallback.recordMailboxProof("op-a", "dav-a");
  console.log(compact("durable-acks", fallback.snapshot()));

  const lifecycle = new TransportCoordinator();
  lifecycle.enqueue("op-b");
  lifecycle.observeRoute("WEBRTC_DIRECT", "AVAILABLE");
  lifecycle.loseWorker();
  lifecycle.loseOffscreen();
  lifecycle.wake();
  console.log(compact("lifecycle-loss", lifecycle.snapshot()));

  const mailboxes = new TransportCoordinator();
  mailboxes.enqueue("op-c");
  mailboxes.configureMailbox("dav-a");
  mailboxes.configureMailbox("dav-b");
  mailboxes.recordMailboxProof("op-c", "dav-a");
  mailboxes.recordMailboxProof("op-c", "dav-b");
  mailboxes.failMailbox("dav-a", "ROLLBACK");
  console.log(compact("independent-mailboxes", mailboxes.snapshot()));
  mailboxes.failMailbox("dav-b", "AUTH_FAILED");
  mailboxes.rotateCredential("dav-b");
  console.log(compact("credential-rotation", mailboxes.snapshot()));
}

main();
