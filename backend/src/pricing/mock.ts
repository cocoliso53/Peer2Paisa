import type { LockQuoteEffect } from '../types/fsm'

const MOCK_MARKET_RATE = 18
const USDC_DECIMALS = 6

const roundMxn = (value: number): string => value.toFixed(2)

const roundUsdcDown = (value: number): string => {
    const factor = 10 ** USDC_DECIMALS
    return (Math.floor(value * factor) / factor).toFixed(USDC_DECIMALS)
}

export const lockMockQuote = ({
    amount,
    premiumPct,
    sell,
}: LockQuoteEffect) => {
    const marketRate = MOCK_MARKET_RATE
    const lockedRate = marketRate * (1 + Number(premiumPct) / 100)

    if (sell) {
        const lockedTokenAmount = roundUsdcDown(Number(amount))
        const lockedFiatAmount = roundMxn(Number(lockedTokenAmount) * lockedRate)

        return {
            marketRate: marketRate.toFixed(2),
            lockedRate: lockedRate.toFixed(2),
            lockedFiatAmount,
            lockedTokenAmount,
        }
    }

    const lockedFiatAmount = roundMxn(Number(amount))
    const lockedTokenAmount = roundUsdcDown(Number(lockedFiatAmount) / lockedRate)

    return {
        marketRate: marketRate.toFixed(2),
        lockedRate: lockedRate.toFixed(2),
        lockedFiatAmount,
        lockedTokenAmount,
    }
}
