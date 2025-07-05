import dotenv from 'dotenv'
import path from 'path'

dotenv.config({
  path: path.resolve(__dirname, '../../.env'),
})

import { createPublicClient, createWalletClient, http, parseEventLogs, parseUnits } from 'viem'
import { privateKeyToAccount } from 'viem/accounts'
import { arbitrumSepolia} from 'viem/chains'
import { SimpleEscrowAbi } from './abi/SimpleEscrow'
import { EscrowFacotryAbi } from './abi/EscrowFactory'

const privateKey = process.env.PRIVATE_KEY
const alchemyURL = process.env.ALCHEMY_URL 
const factoryAddress = process.env.FACTORY_ADDRESS

const account = privateKeyToAccount(privateKey as `0x${string}`)
console.log("address", account)

const publicClient = createPublicClient({
    chain: arbitrumSepolia,
    transport: http(alchemyURL)
})

const walletClient = createWalletClient({
    chain: arbitrumSepolia,
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

const checkAmount = async (escrowAddress: string) => {
    const amount = await publicClient.readContract({
        address: escrowAddress as `0x${string}`,
        abi: SimpleEscrowAbi,
        functionName: 'amount'
    })

    console.log("amount", amount)
}

const markAsFunded = async (escrowAddress: string) => {
    const txHash = await walletClient.writeContract({
        address: escrowAddress as `0x${string}`,
        abi: SimpleEscrowAbi,
        functionName: 'markAsFunded'
    })

    console.log("txHash", txHash)
}

const confirmFiatReceived = async (escrowAddress: string) => {
    const txHash = await walletClient.writeContract({
        address: escrowAddress as `0x${string}`,
        abi: SimpleEscrowAbi,
        functionName: 'confirmFiatReceived'
    })
}

const refundSeller = async (escrowAddress: string) => {
    const txHash = await walletClient.writeContract({
        address: escrowAddress as `0x${string}`,
        abi: SimpleEscrowAbi,
        functionName: 'refundSeller'
    })

    console.log("txHash", txHash)
}

const createEscrow = async (seller: string, buyer: string, amount: bigint) => {
    
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



(async () => {
    console.log("acá")
    //await createEscrow("0x5fcaf1bc20f902cCeEd5bA5Ca2f651da684eca5b", "0xD64F77C974bC81fB80BC52B72Ef2a98398745521", parseUnits("10",6))
    //await checkIsFunded("0xD835eed156EA06Cfe8728c6a81E7aE87E490719E")
    //await checkAmount("0xD835eed156EA06Cfe8728c6a81E7aE87E490719E")
    await refundSeller("0xD835eed156EA06Cfe8728c6a81E7aE87E490719E")
    //await confirmFiatReceived("0xcbd023BEdf797f8057FbFD0f9ca87b567C31164A")
})()