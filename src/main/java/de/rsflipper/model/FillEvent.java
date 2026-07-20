package de.rsflipper.model;

import lombok.Value;

/**
 * Ein Fill-Inkrement eines Offers (SPEC §12.4a): Grundlage für Live-Preisdaten
 * und serverseitiges Buy-Limit-Tracking. Wird gebündelt mit dem nächsten
 * Suggestion-Request übertragen — kein eigener Traffic-Takt.
 */
@Value
public class FillEvent
{
	String id;        // client-generierte UUID → serverseitiger Dedup (SPEC §12.6)
	long ts;          // Unix-Millis der Beobachtung
	int itemId;
	String side;      // "buy" oder "sell" (aus unserer Sicht)
	int qtyDelta;     // neu gefüllte Stückzahl
	long avgPrice;    // Durchschnittspreis dieses Inkrements (spentDelta / qtyDelta)
	/**
	 * true = beim Login-Abgleich gegen den persistierten Slot-Zustand erkannt (Offline-Fill).
	 * Zählt fürs Profit-Tracking, aber NICHT für Live-Preisdaten (§12.4a) — der echte
	 * Handelszeitpunkt lag irgendwann während der Offline-Zeit.
	 */
	boolean login;
}
