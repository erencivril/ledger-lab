package dev.erencivril.ledger.application;

import dev.erencivril.ledger.application.Ports.NewJournalEntry;
import dev.erencivril.ledger.domain.Transfer;
import java.util.List;

/** A transfer row and its journal entries, ready to be written in the open transaction. */
record Posting(Transfer transfer, List<NewJournalEntry> entries) {}
