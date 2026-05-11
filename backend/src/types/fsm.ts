export type OrderStatus = 'active' | 'canceled' | 'done'

export type OrderState =
    | 's0'
    | 'waitingNewOrderAmount'
    | 'waitingPremium'
    | 'waitingSetAddress'
    | 'watingCounterpart'
    | 'watingCounterpartSetAmount'
    | 'watingCounterpartAddress'
    | 'waitingFunding'
    | 'waitingRelease'
    | 'finished'
    | 'canceled'

export type Participant = {
    username: string
    chatId?: number
    address?: string
}

export type OrderDocument = {
    _id?: string
    _rev?: string
    orderId: string
    state: OrderState
    status: OrderStatus
    participants?: string[]
    sell?: boolean
    lastMessageId?: number
    orderMessageId?: number
    amount?: string
    range?: boolean
    asset?: 'USDC'
    makerDenomination?: 'USDC' | 'MXN'
    premiumPct?: string
    marketRate?: string
    lockedRate?: string
    lockedFiatAmount?: string
    lockedTokenAmount?: string
    buyer?: Participant
    seller?: Participant
    escrowAddress?: string
    error?: string
}

export type DeleteDetails = {
    chat: 'ctx' | 'channel' | 'current'
    id?: number
}

export type KeyboardMessage = {
    text: string
    callback: string
}

export type OrderBookMessage = {
    text: string
    keyboard: KeyboardMessage
}

export type SendMessageData = {
    chatId: number
    text: string
    parseMode?: 'Markdown'
}

export type CreateEscrowEffect = {
    buyerAddress: string
    sellerAddress: string
    amount: string
}

export type LockQuoteEffect = {
    amount: string
    premiumPct: string
    sell: boolean
    makerDenomination: 'USDC' | 'MXN'
    asset: 'USDC'
}

export type CancelEscrowEffect = {
    escrowAddress: string
    markAsFundedFirst?: boolean
}

export type FsmEffects = {
    reply?: string[]
    deleteMessage?: DeleteDetails[]
    orderBookMessage?: OrderBookMessage[]
    lockQuote?: LockQuoteEffect
    createEscrow?: CreateEscrowEffect
    cancelEscrow?: CancelEscrowEffect
    sendMessage?: SendMessageData[]
    answerQuery?: boolean
    noEffect?: boolean
    error?: string
}

export type FsmEvent =
    | {
        event: 'buy' | 'sell'
        data: {
            username: string
            messageId?: number
        }
    }
    | {
        event: 'cancel'
        data?: {
            username?: string
        }
    }
    | {
        event: 'setLastMessageId'
        data: {
            messageId: number
        }
    }
    | {
        event: 'setMessagesIds'
        data: {
            messageId?: number
            orderMessageId: number
        }
    }
    | {
        event: 'setAmount' | 'setAmountCounterpart'
        data: {
            text: string
            messageId?: number
        }
    }
    | {
        event: 'setPremium'
        data: {
            text: string
            messageId?: number
        }
    }
    | {
        event: 'setAddress'
        data: {
            text: string
            user: Participant
            messageId?: number
        }
    }
    | {
        event: 'setCounterpartAddress'
        data: {
            counterpart: Participant
        }
    }
    | {
        event: 'takeOrder' | 'takeOrderRange'
        data: {
            counterpart: Participant
            takerChatId?: number
        }
    }
    | {
        event: 'textInput'
        data: {
            text: string
            user?: Participant
            counterpart?: Participant
        }
    }
    | {
        event: 'setEscrowAddress' | 'escrowCreated'
        data: {
            escrow: string
            escrowUrl: string
        }
    }
    | {
        event: 'quoteLocked'
        data: {
            marketRate: string
            lockedRate: string
            lockedFiatAmount: string
            lockedTokenAmount: string
        }
    }
    | {
        event: 'quoteLockFailed' | 'escrowCreationFailed' | 'escrowFunded' | 'escrowFundingFailed' | 'releaseFailed'
        data?: Record<string, never>
    }
    | {
        event: 'fundsReleased'
        data: {
            explorerUrl: string
        }
    }

export type FsmResult = {
    state: OrderDocument
    effects: FsmEffects
}

export type CouchFindResponse<T> = {
    docs: T[]
    warning?: string
    bookmark?: string
}

export type CouchWriteResponse = {
    ok: boolean
    id: string
    rev: string
}
