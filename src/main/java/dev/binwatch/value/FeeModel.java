package dev.binwatch.value;

import dev.binwatch.config.BinWatchConfig;

/** Configurable Auction House cost model for relisting a flipped item as BIN. */
public final class FeeModel {
	private final BinWatchConfig.Fees fees;

	public FeeModel(BinWatchConfig.Fees fees) {
		this.fees = fees;
	}

	public long listingFee(long salePrice) {
		for (BinWatchConfig.FeeTier tier : fees.listingFeeTiers) {
			if (tier.upToPrice == null || salePrice < tier.upToPrice) {
				return Math.round(salePrice * tier.percent / 100.0);
			}
		}
		return 0;
	}

	public long claimTax(long salePrice) {
		return salePrice > fees.claimTaxThreshold ? Math.round(salePrice * fees.claimTaxPercent / 100.0) : 0;
	}

	/** Total costs of selling at {@code salePrice}: listing fee, claim tax and the configured extra cost. */
	public long totalCosts(long salePrice) {
		return listingFee(salePrice) + claimTax(salePrice) + fees.extraCostPerFlip;
	}
}
