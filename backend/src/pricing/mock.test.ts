import test from 'node:test'
import assert from 'node:assert/strict'
import { lockMockQuote } from './mock'

test('locks sell quotes against the mocked market rate', () => {
    const quote = lockMockQuote({
        amount: '100',
        premiumPct: '5',
        sell: true,
        makerDenomination: 'USDC',
        asset: 'USDC',
    })

    assert.deepEqual(quote, {
        marketRate: '18.00',
        lockedRate: '18.90',
        lockedFiatAmount: '1890.00',
        lockedTokenAmount: '100.000000',
    })
})

test('locks buy quotes and rounds USDC down to token precision', () => {
    const quote = lockMockQuote({
        amount: '1800',
        premiumPct: '-5',
        sell: false,
        makerDenomination: 'MXN',
        asset: 'USDC',
    })

    assert.deepEqual(quote, {
        marketRate: '18.00',
        lockedRate: '17.10',
        lockedFiatAmount: '1800.00',
        lockedTokenAmount: '105.263157',
    })
})

test('rounds fiat to two decimals and token amounts down to six decimals', () => {
    const sellQuote = lockMockQuote({
        amount: '0.333333',
        premiumPct: '0',
        sell: true,
        makerDenomination: 'USDC',
        asset: 'USDC',
    })

    const buyQuote = lockMockQuote({
        amount: '1',
        premiumPct: '0',
        sell: false,
        makerDenomination: 'MXN',
        asset: 'USDC',
    })

    assert.equal(sellQuote.lockedFiatAmount, '6.00')
    assert.equal(buyQuote.lockedTokenAmount, '0.055555')
})
