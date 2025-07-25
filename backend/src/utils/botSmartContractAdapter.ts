import { parseUnits } from "viem";
import { createEscrow, markAsFunded, confirmFiatReceived, refundSeller, checkAmount, swapEscrow } from "../escrow/main";

export const createEscrowFromBot = async (sellerAddres: string, buyerAddress: string, amountMXNB: string) => {
    const parsedAmount = parseUnits(amountMXNB, 6) // NOTE: We are hardcoding 6 here, would be better to cerate a const file or something
    const escrowAddress = createEscrow(sellerAddres, buyerAddress, parsedAmount)
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

export  const refundSellerFromBot = async (escrowAddrress: string) => {
    try {
        const txHash = await refundSeller(escrowAddrress)
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

export const swapAndSendFromBot = async (escrowAddress: string) => {
    try {
        const USDT = "0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9" // USDT hardcoded for now
        const txHash = await swapEscrow(escrowAddress as `0x${string}`, USDT)
        return txHash
    } catch (error) {
        return null
    }
}