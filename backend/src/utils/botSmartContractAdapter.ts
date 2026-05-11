import { parseUnits } from "viem";
import { createEscrow, markAsFunded, confirmFiatReceived, refundSeller, checkAmount, swapEscrow } from "../escrow/main";

const USDC_DECIMALS = 6

type CancelEscrowResult =
    | { status: 'not_needed' }
    | { status: 'refunded', txHash: string }
    | { status: 'failed' }

export const createEscrowFromBot = async (sellerAddres: string, buyerAddress: string, amountUsdc: string) => {
    const parsedAmount = parseUnits(amountUsdc, USDC_DECIMALS)
    const escrowAddress = await createEscrow(sellerAddres, buyerAddress, parsedAmount)
    return escrowAddress
}

export const markAsFundedFromBot = async (escrowAddress: string) => {
    try {
        const txHash = await markAsFunded(escrowAddress)
        return txHash
    } catch (error) {
        console.log("Error markAsFundedFromBot", error)
        return null
    }
}

export const confirmFiatReceivedFromBot = async (escrowAddress: string) =>  {
    try {
        const txHash = await confirmFiatReceived(escrowAddress)
        return txHash
    } catch (error) {
        console.log("Error confirmFiatReceivedFromBot", error)
        return null
    }
}

export  const refundSellerFromBot = async (escrowAddress: string) => {
    try {
        const txHash = await refundSeller(escrowAddress)
        return txHash
    } catch (error) {
        console.log("Error refundSellerFromBot", error)
        return null
    }
}

export const checkAmountFromBot = async (escrowAddress: string) => {
    try {
        const amount = await checkAmount(escrowAddress)
        return amount
    } catch (error) {
        return null
    }
}

export const cancelEscrowFromBot = async (
    escrowAddress: string,
    markAsFundedFirst = false,
): Promise<CancelEscrowResult> => {
    try {
        const amount = await checkAmount(escrowAddress)

        if (!amount || amount <= 0n) {
            return { status: 'not_needed' }
        }

        if (markAsFundedFirst) {
            const marked = await markAsFunded(escrowAddress)
            if (!marked) {
                return { status: 'failed' }
            }
        }

        const txHash = await refundSeller(escrowAddress)
        if (!txHash) {
            return { status: 'failed' }
        }

        return { status: 'refunded', txHash }
    } catch (error) {
        console.log("Error cancelEscrowFromBot", error)
        return { status: 'failed' }
    }
}

/*
export const swapAndSendFromBot = async (escrowAddress: string) => {
    try {
        const USDT = "0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9" // USDT hardcoded for now
        const txHash = await swapEscrow(escrowAddress as `0x${string}`, USDT)
        return txHash
    } catch (error) {
        return null
    }
}
*/
