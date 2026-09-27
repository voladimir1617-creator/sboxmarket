"""The SHIPPED fee arithmetic, transcribed from source. Sources:
  platform take   TradeService.groovy:46,539  (price*0.02).setScale(2, HALF_UP)
  deposit cost    PlatformLedgerService.estimateProcessingCost  2.9% + $0.30 HALF_UP
  payout cost     PlatformLedgerService.estimatePayoutCost      0.25% + $0.25 HALF_UP
  fee CHARGED     PlatformLedgerService.feeCharged              same rates, FLOOR
  rates           application.yml:370-378
"""
from decimal import Decimal, ROUND_HALF_UP, ROUND_FLOOR

FEE_RATE      = Decimal("0.02")
PROC_PCT      = Decimal("2.9");  PROC_FIX  = Decimal("0.30")
PAYOUT_PCT    = Decimal("0.25"); PAYOUT_FIX = Decimal("0.25")
C = Decimal("0.01")

def _d(x): return x if isinstance(x, Decimal) else Decimal(str(x))

def platform_take(price):
    """TradeService.groovy:539 — HALF_UP, so price < $0.25 yields exactly $0.00."""
    return float((_d(price) * FEE_RATE).quantize(C, ROUND_HALF_UP))

def stripe_deposit_cost(amount):
    return float(((_d(amount) * PROC_PCT / 100) + PROC_FIX).quantize(C, ROUND_HALF_UP))

def stripe_payout_cost(gross):
    return float(((_d(gross) * PAYOUT_PCT / 100) + PAYOUT_FIX).quantize(C, ROUND_HALF_UP))

def payout_fee_charged(gross):
    """What the USER is actually debited — FLOOR (PlatformLedgerService.feeCharged)."""
    return float(((_d(gross) * PAYOUT_PCT / 100) + PAYOUT_FIX).quantize(C, ROUND_FLOOR))

def seller_net(price):
    """Seller proceeds after ONE sale and ONE withdrawal of the whole balance."""
    p = _d(price)
    fee = _d(platform_take(price))
    gross = p - fee
    return float(gross - _d(payout_fee_charged(gross)))
