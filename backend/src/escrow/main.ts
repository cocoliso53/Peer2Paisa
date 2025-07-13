import dotenv from 'dotenv'
import path from 'path'

dotenv.config({
  path: path.resolve(__dirname, '../../.env'),
})

import { createPublicClient, createWalletClient, http, parseUnits, parseEventLogs, Address } from 'viem'
import { privateKeyToAccount } from 'viem/accounts'
import { arbitrum} from 'viem/chains'
import { SimpleEscrowAbi } from './abi/SimpleEscrow'
import { EscrowFacotryAbi } from './abi/EscrowFactory'
import { SwapEscrowAbi } from './abi/SwapEscrowAbi'
import { QuoterAbi } from './abi/QuoterAbi'

const privateKey = process.env.PRIVATE_KEY
const alchemyURL = process.env.ALCHEMY_URL 
const factoryAddress = process.env.FACTORY_ADDRESS
const QUOTER_ADDR = "0xb27308f9F90D607463bb33eA1BeBb41C27CE5AB6" as Address
const SLIPPAGE_BPS  = 50

const account = privateKeyToAccount(privateKey as `0x${string}`)

const publicClient = createPublicClient({
    chain: arbitrum,
    transport: http(alchemyURL)
})

const walletClient = createWalletClient({
    chain: arbitrum,
    transport: http(alchemyURL),
    account
})

const checkIsFunded = async (escrowAddress: string) => {
    const isFunded = await publicClient.readContract({
        address: escrowAddress as `0x${string}`,
        abi: SimpleEscrowAbi,
        functionName: 'isFunded'
    })

    console.log("isFunded", isFunded)
}

export const checkAmount = async (escrowAddress: string) => {
    const amount = await publicClient.readContract({
        address: escrowAddress as `0x${string}`,
        abi: SimpleEscrowAbi,
        functionName: 'amount'
    })

    console.log("amount", amount)
    return amount
}

export const markAsFunded = async (escrowAddress: string) => {
    const txHash = await walletClient.writeContract({
        address: escrowAddress as `0x${string}`,
        abi: SimpleEscrowAbi,
        functionName: 'markAsFunded'
    })

    console.log("txHash", txHash)
    return txHash
}

export const confirmFiatReceived = async (escrowAddress: string) => {
    const txHash = await walletClient.writeContract({
        address: escrowAddress as `0x${string}`,
        abi: SimpleEscrowAbi,
        functionName: 'confirmFiatReceived'
    })

    console.log("txHash confirmedFiatReceived", txHash)
    return txHash
}

export const refundSeller = async (escrowAddress: string) => {
    const txHash = await walletClient.writeContract({
        address: escrowAddress as `0x${string}`,
        abi: SimpleEscrowAbi,
        functionName: 'refundSeller'
    })

    console.log("txHash", txHash)
}

export const createEscrow = async (seller: string, buyer: string, amount: bigint) => {
    
    const txHash = await walletClient.writeContract({
        address: factoryAddress as `0x${string}`,
        abi: EscrowFacotryAbi,
        functionName: 'createEscrow',
        args: [seller as `0x${string}`, buyer as `0x${string}`, amount]
    })

    console.log("Escrow creado", txHash)

    const receipt = await publicClient.waitForTransactionReceipt({
        hash: txHash
    })

    const logs = parseEventLogs({
        abi: EscrowFacotryAbi,
        eventName: 'EscrowCreated',
        logs: receipt.logs,
    })

    const deployedAddress = logs[0]?.args.escrowAddress
    console.log('✅ New escrow deployed at:', deployedAddress)
    return deployedAddress

}

export const swapEscrow = async (
  escrowAddress: Address,
  buyToken:      Address,
) => {
  // check MXNB amount
  const amountIn: bigint = await checkAmount(escrowAddress)

  if (amountIn === 0n) {
    throw new Error('No MXNB in escrow')
  }

  // Get buy amount from Uniswap V3 Quoter
  const quotedAmountOut: bigint = await publicClient.readContract({
    address:       QUOTER_ADDR,
    abi:           QuoterAbi,
    functionName:  'quoteExactInputSingle',
    args: [
      '0xF197FFC28c23E0309B5559e7a166f2c6164C80aA' as Address, //MXNB
      buyToken,
      3000,
      amountIn,
      0n,
    ],
  })

  // apply slippage tolerance
  const minBuyAmount = quotedAmountOut * (10_000n - BigInt(SLIPPAGE_BPS)) / 10_000n

  const functionName = 'swapAndRelease' as const
  const tx = await walletClient.writeContract({
    address:      escrowAddress,
    abi:          SwapEscrowAbi,
    functionName,
    args: [
      buyToken,        // address buyToken
      3000,            
      minBuyAmount,    // uint256 minBuyAmount
      0n,              
    ],
  })

  console.log(`Submitted swapAndRelease: ${tx}`)
  return tx
}



/*
(async () => {
    console.log("acá")
    //await createEscrow("0x5fcaf1bc20f902cCeEd5bA5Ca2f651da684eca5b", "0xD64F77C974bC81fB80BC52B72Ef2a98398745521", parseUnits("10",6))
    //await checkIsFunded("0xf70268Cf6684FA9e016d1bDE436441BB8aAEf1B0")
    await checkAmount("0x2fbd4931232C3456c68269c6516800884A08180d")
    //await refundSeller("0xf70268Cf6684FA9e016d1bDE436441BB8aAEf1B0")
    //await markAsFunded("0x2fbd4931232C3456c68269c6516800884A08180d")
    await swapEscrow("0x2fbd4931232C3456c68269c6516800884A08180d", "0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9")
    //await confirmFiatReceived("0x3efbd2D9926A8F1e82Cbc86345d4A17A7bf04012")
})()
    */
