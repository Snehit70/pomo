export const DB_NAME = "pomo";

export const SCHEMA_VERSION = 4;

export const SYNC_OPERATION_STORE = "syncOperations";
export const SYNC_FEED_HEAD_STORE = "syncFeedHeads";
export const SYNC_PREFERENCE_STORE = "syncPreferences";
export const SYNC_OUTBOX_STORE = "syncOutbox";
export const SYNC_DISPOSITION_EVENT_STORE = "syncDispositionEvents";

type IndexDef = readonly [name: string, keyPath: string | string[]];

function ensureStore(
  db: IDBDatabase,
  transaction: IDBTransaction,
  name: string,
  options: IDBObjectStoreParameters,
  indexes: readonly IndexDef[],
): void {
  let store: IDBObjectStore;
  if (!db.objectStoreNames.contains(name)) {
    store = db.createObjectStore(name, options);
  } else {
    store = transaction.objectStore(name);
  }
  for (const [indexName, keyPath] of indexes) {
    if (!store.indexNames.contains(indexName)) store.createIndex(indexName, keyPath);
  }
}

export function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, SCHEMA_VERSION);
    request.onupgradeneeded = (event) => {
      const db = request.result;
      const transaction = request.transaction!;
      if (event.oldVersion < 2) {
        // v1 → v2: settings moved to chrome.storage.local; drop the legacy store.
        if (db.objectStoreNames.contains("settings")) db.deleteObjectStore("settings");
      }
      ensureStore(db, transaction, "sessions", { keyPath: "start" }, [["date", "date"]]);
      ensureStore(db, transaction, "dayStats", { keyPath: "date" }, []);
      ensureStore(db, transaction, "crewSnapshots", { keyPath: ["crewId", "identityPublicKey"] }, [["crewId", "crewId"]]);
      ensureStore(
        db,
        transaction,
        "crewDailyAggregates",
        { keyPath: ["crewId", "identityPublicKey", "localDate"] },
        [
          ["crewId_key", ["crewId", "identityPublicKey"]],
          ["crewId", "crewId"],
        ],
      );
      ensureStore(db, transaction, "crewHiddenMembers", { keyPath: ["crewId", "identityPublicKey"] }, [["crewId", "crewId"]]);
      ensureStore(db, transaction, "crewRelayState", { keyPath: ["crewId", "relayUrl"] }, []);
      // v3 → v4 is additive. Existing timer/history/Crew data is untouched;
      // dormant sync state starts empty and is populated only by issue #103's DAO.
      ensureStore(
        db,
        transaction,
        SYNC_OPERATION_STORE,
        { keyPath: "operationId" },
        [
          ["feedPosition", ["feedKey", "sequence"]],
          ["disposition", "disposition"],
        ],
      );
      ensureStore(db, transaction, SYNC_FEED_HEAD_STORE, { keyPath: "feedKey" }, []);
      ensureStore(db, transaction, SYNC_PREFERENCE_STORE, { keyPath: "key" }, []);
      ensureStore(db, transaction, SYNC_OUTBOX_STORE, { keyPath: "operationId" }, [["state", "state"]]);
      ensureStore(
        db,
        transaction,
        SYNC_DISPOSITION_EVENT_STORE,
        { keyPath: "id", autoIncrement: true },
        [["disposition", "disposition"]],
      );
    };
    request.onsuccess = () => {
      resolve(request.result);
    };
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error("indexedDB open blocked"));
  });
}
