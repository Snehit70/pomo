import type { OperationJournal, OperationJournalEntry } from "../kernel/OperationKernel";
import type { RejectedDisposition } from "../protocol/types";
import { IndexedDbOperationDao } from "./IndexedDbOperationDao";

/** Dormant persistence adapter. Runtime wiring is deliberately left to a later issue. */
export class IndexedDbKernelJournal implements OperationJournal {
  constructor(private readonly dao: IndexedDbOperationDao = new IndexedDbOperationDao()) {}

  async recordBatch(entries: readonly OperationJournalEntry[]): Promise<void> {
    await this.dao.commitBatch(entries.map(({ operation, disposition, localAuthor }) => ({
      operation,
      disposition,
      localAuthor,
    })));
  }

  async recordRejected(rawWire: Uint8Array, disposition: RejectedDisposition): Promise<void> {
    await this.dao.recordRejected(rawWire, disposition);
  }
}
