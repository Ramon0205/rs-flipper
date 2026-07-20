package de.rsflipper.model;

import lombok.Value;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

/**
 * Zustand eines GE-Slots für den AccountStatus (SPEC §4.2).
 * Enthält die Signale, die der Server für Abort-/Re-Price-Entscheidungen braucht (§4.3.6):
 * Preis, Gesamtmenge, gefüllte Menge, ausgegebenes GP — Offer-Alter trackt der Server selbst.
 */
@Value
public class OfferSnapshot
{
	int boxId;
	String state;
	int itemId;
	int price;
	int totalQuantity;
	int quantityTraded;
	long spent;
	boolean active;
	/** true, wenn der Preis unserem Vorschlag entsprach (ab M4 gesetzt; §4.3.6). */
	boolean suggestedPriceUsed;

	public static OfferSnapshot from(int slot, GrandExchangeOffer offer, boolean suggestedPriceUsed)
	{
		GrandExchangeOfferState st = offer.getState();
		boolean active = st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.SELLING;
		return new OfferSnapshot(
			slot,
			st.name(),
			offer.getItemId(),
			offer.getPrice(),
			offer.getTotalQuantity(),
			offer.getQuantitySold(),
			offer.getSpent(),
			active,
			suggestedPriceUsed
		);
	}
}
